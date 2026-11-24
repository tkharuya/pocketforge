# Google Identity dependencies

Pinned entry point: `com.google.android.gms:play-services-auth:22.0.0`.

Downloaded from Google Maven and Maven Central. `dependencies.json` records every resolved coordinate, original POM, binary path, SHA-256, runtime JAR, AAR resources and manifest. Original artifacts and their notices remain under `maven/` and `unpacked/`.

To refresh these pinned artifacts, run `python3 third_party/fetch_google_auth.py`. The script mediates this fixed POM dependency graph by highest requested version; it is not a general Gradle resolver. It excludes optional/test/provided dependencies and lint JARs. The Kotlin multiplatform coroutine POM resolves to its JVM implementation.

For Gradle use `implementation("com.google.android.gms:play-services-auth:22.0.0")`. For the SDK-only build, consume the `jars`, `resources`, and `manifest` fields of `dependencies.json`. Compile and package every runtime JAR; merge all AAR resources and application manifest declarations; generate R classes for each AAR manifest package. Minimum SDK across this graph is 24. Authentication was compiled against API 36.

Run `python3 third_party/collect_notices.py` before packaging. It preserves the original POMs, AAR root license files, and any JAR META-INF license/notice files in `app/src/main/assets/third_party_notices/`. Its offline `index.html` and JSON catalog make these notices accessible in the source and APK. The collection process does not fetch remote license pages during a build.

The runtime class scan found no duplicate .class entries. Real Google authorization still needs the developer's own Cloud project, enabled Drive API, and Android OAuth registration for package name plus the APK signing certificate SHA-1. No OAuth client secret or user credential is included here.

| Coordinate | License declared in POM |
|---|---|
| `androidx.activity:activity:1.5.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.annotation:annotation:1.2.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.annotation:annotation-experimental:1.1.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.arch.core:core-common:2.1.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.arch.core:core-runtime:2.1.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.collection:collection:1.1.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.concurrent:concurrent-futures:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.core:core:1.8.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.core:core-ktx:1.2.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.customview:customview:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.fragment:fragment:1.5.7` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-common:2.5.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-livedata-core:2.5.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-runtime:2.5.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel:2.5.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.5.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.loader:loader:1.1.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.savedstate:savedstate:1.2.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.tracing:tracing:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.versionedparcelable:versionedparcelable:1.1.1` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `androidx.viewpager:viewpager:1.0.0` | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `com.google.android.gms:play-services-auth:22.0.0` | [Android Software Development Kit License](https://developer.android.com/studio/terms.html) |
| `com.google.android.gms:play-services-auth-api-phone:18.0.2` | [Android Software Development Kit License](https://developer.android.com/studio/terms.html) |
| `com.google.android.gms:play-services-auth-base:18.0.10` | [Android Software Development Kit License](https://developer.android.com/studio/terms.html) |
| `com.google.android.gms:play-services-base:18.5.0` | [Android Software Development Kit License](https://developer.android.com/studio/terms.html) |
| `com.google.android.gms:play-services-basement:18.9.0` | [Android Software Development Kit License](https://developer.android.com/studio/terms.html) |
| `com.google.android.gms:play-services-fido:20.0.1` | [Android Software Development Kit License](https://developer.android.com/studio/terms.html) |
| `com.google.android.gms:play-services-tasks:18.2.0` | [Android Software Development Kit License](https://developer.android.com/studio/terms.html) |
| `com.google.guava:listenablefuture:1.0` | See original artifact/POM |
| `org.jetbrains:annotations:23.0.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-stdlib:1.9.21` | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlin:kotlin-stdlib-common:1.8.21` | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0` | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) |
