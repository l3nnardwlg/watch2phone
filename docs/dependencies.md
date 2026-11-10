# Third-party dependencies

The project license is [MIT](../LICENSE). The table records direct build and app dependencies. Gradle resolves transitive dependencies from Google Maven, Maven Central and the Gradle Plugin Portal.

| Dependency | Version | License or terms |
| --- | --- | --- |
| Kotlin Gradle plugin, Compose compiler plugin and Kotlin standard library | 2.0.21 | [Apache 2.0](https://github.com/JetBrains/kotlin/blob/v2.0.21/license/LICENSE.txt) |
| Android Gradle Plugin | 8.7.3 | [Apache 2.0 source](https://android.googlesource.com/platform/tools/base/) |
| AndroidX Activity Compose, Lifecycle Compose and Wear Compose Material | 1.9.3, 2.8.7, 1.4.1 | [Apache 2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) |
| JUnit 4, test-only | 4.13.2 | [Eclipse Public License 1.0](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) |
| Gradle distribution and checked-in wrapper | 8.10.2 | [Apache 2.0](https://github.com/gradle/gradle/blob/v8.10.2/LICENSE) |
| SwiftUI and Core Bluetooth | iOS SDK | Apple platform frameworks under Apple's SDK terms. No third-party Swift packages. |

The checked-in Gradle wrapper scripts retain their upstream headers and the wrapper JAR includes its license in `META-INF/LICENSE`. The Gradle distribution includes its own `LICENSE` and `NOTICE` files. To inspect resolved runtime and test dependencies, run `bash gradlew :app:dependencies` in `apps/wearos`. Preserve dependency licenses and applicable notices when redistributing binaries.

The three projects in the [research review](research.md#reference-implementation-review) are references. Their code is not included.
