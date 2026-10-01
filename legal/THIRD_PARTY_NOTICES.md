# Third-party notices

This file provides attribution and license notices for third-party software
used by the ELU Android SDK.

## Runtime dependencies

ELU Android `0.1.0` uses these exact Maven coordinates:

- `com.posthog:posthog-android:3.58.0`
- `com.posthog:posthog:6.29.0` (transitive runtime)

The source release is tag
`android-v3.58.0`, whose annotated tag object is
`fdfb71de8f89e5a57cfc647b6182adfe36fadddd` and whose peeled source commit is
`279bd1c946ab810d770380472608cc4a01d01025`.

The dependency is distributed under the MIT License. Copyright and permission
notices for redistributed source or binaries must be preserved here or in
another `LICENSE*` / `THIRD_PARTY_NOTICES*` legal file.

Source: <https://github.com/PostHog/posthog-android/tree/android-v3.58.0>

The published ELU `0.1.0` AAR does not bundle dependency classes. Maven resolves
the runtime and its Kotlin, AndroidX, coroutines, Gson, OkHttp, Okio, Curtains,
annotations, and listenable-future dependencies as separate artifacts. The
compatibility baseline contains a normalized closure summary that points back
to the exact coordinates above. A fresh license closure audit is required
before source from those artifacts is redistributed or bundled.

## License text

The text below is copied verbatim from `LICENSE.md` at peeled commit
`279bd1c946ab810d770380472608cc4a01d01025`.

```text
MIT License

Copyright (c) [2023] [PostHog]

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

Some files in this codebase contain code from getsentry/sentry-android-gradle-plugin.
In such cases it is explicitly stated in the file header. This license only applies to the relevant code in such cases.

MIT License

Copyright (c) 2020 Sentry

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Scanner configuration

The legal-only scanner reads the following case-insensitive token. It must not
be copied into source, generated metadata, fixtures, or artifacts outside a
`LICENSE*` / `THIRD_PARTY_NOTICES*` path.

<!-- zero-brand-token-start -->
PostHog
<!-- zero-brand-token-end -->


## Owned replay HTTP dependency

The internal ELU replay transport directly declares `com.squareup.okhttp3:okhttp:4.12.0`.
It does not rely on another analytics runtime to own this dependency. OkHttp and
its `com.squareup.okio:okio:3.6.0` dependency (JVM artifact `okio-jvm:3.6.0`)
are published by Square, Inc. under the Apache License, Version 2.0, according
to their Maven POMs. License: <https://www.apache.org/licenses/LICENSE-2.0>.
No third-party source or classes are copied into the SDK AAR by this change;
Maven resolves these separate artifacts and their transitive dependencies.

OkHttp's artifact includes this additional public-suffix-list notice:

> Note that publicsuffixes.gz is compiled from The Public Suffix List:
> https://publicsuffix.org/list/public_suffix_list.dat
>
> It is subject to the terms of the Mozilla Public License, v. 2.0:
> https://mozilla.org/MPL/2.0/

The final resolved dependency/license closure must be included in release
artifact verification; this declaration alone is not a complete closure audit.
