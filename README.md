# AWS SDK for Kotlin

## License

This library is licensed under the Apache 2.0 License. 


## Getting Started

See the [Getting Started Guide](docs/GettingStarted.md)


## Development

### Generate SDK(s)

Generated sources are not checked into the repository, you first have to generate the clients before you can build them.


```sh
./gradlew :codegen:sdk:bootstrap
```

NOTE: This task will respect the AWS services specified by project properties. See options below.
NOTE: To re-run codegen for the same set of services multiple times add the `--rerun-tasks` flag.


After generating the services you care about they are available to build:

e.g.
```sh
./gradlew :services:lambda:build
```


Where the task follows the pattern: `:services:SERVICE:build`

To see list of all projects run `./gradlew projects`

##### Generating a single service
See the local.properties definition above to specify this in a config file.

```sh
./gradlew -Paws.services=+lambda  :codegen:sdk:bootstrap
```

##### Testing Locally
Testing generated services generally requires publishing artifacts (e.g. client-runtime) of `smithy-kotlin`, `aws-crt-kotlin`, and `aws-sdk-kotin` to maven local.

#### Generating API Documentation

API documentation is generated using [Dokka](http://kotlin.github.io/dokka) which is the official documentation tool maintained by JetBrains for documenting Kotlin code.

Unlike Java, Kotlin uses it's own [KDoc](https://kotlinlang.org/docs/kotlin-doc.html) format.


To generate API reference documentation for the AWS Kotlin SDK:


```sh
./gradlew --no-daemon --no-parallel dokkaHtmlMultiModule
```

This will output HTML formatted documentation to `build/dokka/htmlMultiModule`

NOTE: You currently need an HTTP server to view the documentation in browser locally. You can either use the builtin server in Intellij or use your favorite local server (e.g. `python3 -m http.server`). See [Kotlin/dokka#1795](https://github.com/Kotlin/dokka/issues/1795)

### Build Properties

You can define a `local.properties` config file at the root of the project to modify build behavior. 

|Property|Description|
|---|---|
|`compositeProjects`|Specify paths to repos the SDK depends upon such as `smithy-kotlin`|
|`aws.services`|Specify inclusions (+ prefix) and exclusions (- prefix) of service names to generate|
|`aws.protocols`|Specify inclusions (+ prefix) and exclusions (- prefix) of AWS protocols to generate|

#### Composite Projects

Dependencies of the SDK can be added as composite build such that multiple repos may appear as one
holistic source project in the IDE.

```ini
# comma separated list of paths to `includeBuild()`
# This is useful for local development of smithy-kotlin in particular 
compositeProjects=../smithy-kotlin
```

#### Generating Specific Services Based on Name or Protocol

A comma separated list of services to include or exclude for generation from codegen/sdk/aws-models may
be specified with the `aws.services` property. A list of protocols of services to generate may be specified
with the `aws.protocols` property.

Included services require a '+' character prefix and excluded services require a '-' character. 
If any items are specified for inclusion, only specified included members will be generated.  If no items
are specified for inclusion, all members not excluded will be generated.
When unspecified all services found in the directory specified by the `modelsDir` property are generated.
Service names match the filenames in the models directory `service.VERSION.json`.

Some example entries for `local.properties`:
```ini
# Generate only AWS Lambda:
aws.services=+lambda
```

```ini
# Generate all services but AWS location and AWS DynamoDB:
aws.services=-location,-dynamodb
```

```ini
# Generate all services except those using the restJson1 protocol:
aws.protocols=-restJson1
```
### Debugging

See [Debugging](docs/debugging.md)
