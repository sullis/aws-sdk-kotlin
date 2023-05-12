/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.sdk.kotlin.runtime.auth.credentials

import aws.sdk.kotlin.runtime.auth.credentials.internal.sso.SsoClient
import aws.sdk.kotlin.runtime.auth.credentials.internal.sso.getRoleCredentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CloseableCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProviderException
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.serde.json.*
import aws.smithy.kotlin.runtime.time.Clock
import aws.smithy.kotlin.runtime.time.Instant
import aws.smithy.kotlin.runtime.time.fromEpochMilliseconds
import aws.smithy.kotlin.runtime.tracing.*
import aws.smithy.kotlin.runtime.util.*
import kotlin.coroutines.coroutineContext

private const val PROVIDER_NAME = "SSO"

/**
 * [CredentialsProvider] that uses AWS Single Sign-On (AWS SSO) to source credentials. The
 * provider is expected to be configured for the AWS Region where the AWS SSO user portal is hosted.
 *
 * The provider does not initiate or perform the AWS SSO login flow. It is expected that you have
 * already performed the SSO login flow using (e.g. using the AWS CLI `aws sso login`). The provider
 * expects a valid non-expired access token for the AWS SSO user portal URL in `~/.aws/sso/cache`.
 * If a cached token is not found, it is expired, or the file is malformed an exception will be thrown.
 *
 *
 * **Instantiating AWS SSO provider directly**
 *
 * You can programmatically construct the AWS SSO provider in your application, and provide the necessary
 * information to load and retrieve temporary credentials using an access token from `~/.aws/sso/cache`.
 *
 * ```
 * val source = SsoCredentialsProvider(
 *     accountId = "123456789",
 *     roleName = "SsoReadOnlyRole",
 *     startUrl = "https://my-sso-portal.awsapps.com/start",
 *     ssoRegion = "us-east-2"
 * )
 *
 * // Wrap the provider with a caching provider to cache the credentials until their expiration time
 * val ssoProvider = CachedCredentialsProvider(source)
 * ```
 * It is important that you wrap the provider with [CachedCredentialsProvider] if you are programmatically constructing
 * the provider directly. This prevents your application from accessing the cached access token and requesting new
 * credentials each time the provider is used to source credentials.
 *
 *
 * **Additional Resources**
 * * [Configuring the AWS CLI to use AWS Single Sign-On](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sso.html)
 * * [AWS Single Sign-On User Guide](https://docs.aws.amazon.com/singlesignon/latest/userguide/what-is.html)
 */
public class SsoCredentialsProvider public constructor(
    /**
     * The AWS account ID that temporary AWS credentials will be resolved for
     */
    public val accountId: String,

    /**
     * The IAM role in the AWS account that temporary AWS credentials will be resolved for
     */
    public val roleName: String,

    /**
     * The start URL (also known as the "User Portal URL") provided by the SSO service
     */
    public val startUrl: String,

    /**
     * The AWS region where the SSO directory for the given [startUrl] is hosted.
     */
    public val ssoRegion: String,

    /**
     * The SSO Session name from the profile. If a session name is given an [SsoTokenProvider]
     * will be used to fetch tokens.
     */
    public val ssoSessionName: String? = null,

    /**
     * The [HttpClientEngine] to use when making requests to the AWS SSO service
     */
    private val httpClientEngine: HttpClientEngine? = null,

    /**
     * The platform provider
     */
    private val platformProvider: PlatformProvider = PlatformProvider.System,

    /**
     * The source of time for the provider
     */
    private val clock: Clock = Clock.System,

) : CloseableCredentialsProvider {

    private val ssoTokenProvider = ssoSessionName?.let { sessName ->
        SsoTokenProvider(sessName, startUrl, ssoRegion, httpClientEngine = httpClientEngine, platformProvider = platformProvider, clock = clock)
    }

    override suspend fun resolve(attributes: Attributes): Credentials {
        val traceSpan = coroutineContext.traceSpan
        val logger = traceSpan.logger<SsoCredentialsProvider>()

        val token = if (ssoTokenProvider != null) {
            logger.trace { "Attempting to load token using token provider for sso-session: `$ssoSessionName`" }
            ssoTokenProvider.resolve(attributes)
        } else {
            logger.trace { "Attempting to load token from file using legacy format" }
            legacyLoadTokenFile()
        }

        val client = SsoClient {
            region = ssoRegion
            httpClientEngine = this@SsoCredentialsProvider.httpClientEngine
            tracer = traceSpan.asNestedTracer("SSO-")
            // FIXME - create an anonymous credential provider to explicitly avoid default chain creation (technically the transform should remove need for sigv4 cred provider since it's all anon auth)
        }

        val resp = try {
            client.getRoleCredentials {
                accountId = this@SsoCredentialsProvider.accountId
                roleName = this@SsoCredentialsProvider.roleName
                accessToken = token.token
            }
        } catch (ex: Exception) {
            throw CredentialsNotLoadedException("GetRoleCredentials operation failed", ex)
        } finally {
            client.close()
        }

        val roleCredentials = resp.roleCredentials ?: throw CredentialsProviderException("Expected SSO roleCredentials to not be null")

        return Credentials(
            accessKeyId = checkNotNull(roleCredentials.accessKeyId) { "Expected accessKeyId in SSO roleCredentials response" },
            secretAccessKey = checkNotNull(roleCredentials.secretAccessKey) { "Expected secretAccessKey in SSO roleCredentials response" },
            sessionToken = roleCredentials.sessionToken,
            expiration = Instant.fromEpochMilliseconds(roleCredentials.expiration),
            PROVIDER_NAME,
        )
    }

    override fun close() {}

    // non sso-session legacy token flow
    private suspend fun legacyLoadTokenFile(): SsoToken {
        val token = readTokenFromCache(startUrl, platformProvider)
        val now = clock.now()
        if (now > token.expiration) throw ProviderConfigurationException("The SSO session has expired. To refresh this SSO session run `aws sso login` with the corresponding profile.")

        return token
    }
}
