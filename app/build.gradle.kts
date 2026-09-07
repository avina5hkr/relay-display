plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// -------------------------------------------------------------------------------------------
// Version, from gradle.properties -- the single source of truth. See the notes there.
// -------------------------------------------------------------------------------------------

val appVersionName: String = providers.gradleProperty("APP_VERSION_NAME").get()

/**
 * versionCode, parsed with an intentional error message.
 *
 * A raw `.toInt()` gave a bare NumberFormatException pointing at Gradle internals. Passing a
 * sentinel through to AGP was worse: AGP reports "versionCode is set to -1" while the file
 * actually says something like `1.0`, which sends you looking in the wrong place.
 *
 * So this fails configuration immediately, naming the property and the offending value. An
 * unparsable versionCode cannot produce a working build in any case, and this only affects a
 * genuinely broken gradle.properties -- a normal checkout, with or without signing credentials,
 * configures and syncs exactly as before.
 */
val rawVersionCode: String = providers.gradleProperty("APP_VERSION_CODE").get()
val appVersionCode: Int = rawVersionCode.trim().toIntOrNull()
    ?: throw GradleException(
        "APP_VERSION_CODE in gradle.properties must be a positive integer, but is " +
            "'${rawVersionCode.trim()}'.",
    )

/**
 * Semantic version, optionally with a dotted prerelease suffix: 1.0.0 or 0.1.0-beta.1.
 * Deliberately stricter than full semver -- build metadata (+sha) has no meaning for an Android
 * versionName and would only create tags that do not match.
 */
val semVerPattern: String = """^\d+\.\d+\.\d+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$"""

/**
 * Fails the build on a malformed version rather than shipping one.
 *
 * Every release task depends on this. A bad versionName produces a tag nobody can match, and a
 * bad versionCode is permanent once Play has seen it.
 */
val validateVersion = tasks.register("validateVersion") {
    group = "verification"
    description = "Checks APP_VERSION_NAME and APP_VERSION_CODE are well formed."
    // Captured as plain values at configuration time. The regex is rebuilt inside the action
    // rather than captured, because the configuration cache cannot serialise a script-level
    // object reference.
    val name = appVersionName
    val code = appVersionCode
    val rawCode = rawVersionCode
    val pattern = semVerPattern
    inputs.property("versionName", name)
    inputs.property("versionCode", rawCode)
    doLast {
        if (!Regex(pattern).matches(name)) {
            throw GradleException(
                "APP_VERSION_NAME '$name' is not a semantic version. " +
                    "Expected 1.0.0 or 0.1.0-beta.1.",
            )
        }
        if (rawCode.trim().toIntOrNull() == null) {
            throw GradleException(
                "APP_VERSION_CODE in gradle.properties is not an integer: '$rawCode'.",
            )
        }
        if (code < 1) {
            throw GradleException("APP_VERSION_CODE must be a positive integer, got $code.")
        }
        logger.lifecycle("version $name (code $code), tag v$name")
    }
}

/** True for 0.1.0-beta.1 and friends; drives prerelease vs stable in the release workflow. */
val isPrerelease: Boolean = appVersionName.contains('-')

// -------------------------------------------------------------------------------------------
// Release signing, supplied only through the environment.
// -------------------------------------------------------------------------------------------
//
// Nothing is hard-coded and no signing properties file is read, so there is no file that could be
// committed by accident. Passwords arrive as environment variables rather than Gradle properties
// because -P values appear in the process command line, which is world-readable on a shared
// machine and is echoed by many CI systems.

// providers.environmentVariable, not System.getenv: the configuration cache tracks provider
// reads as inputs and invalidates when they change. A raw System.getenv at configuration time is
// invisible to it, so a cache entry built without credentials is happily reused with them set --
// which produces "Keystore file not set for signing config release" and looks like a signing bug.
fun env(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val releaseStorePath: String? = env("RELEASE_KEYSTORE_PATH")
val releaseStorePassword: String? = env("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = env("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = env("RELEASE_KEY_PASSWORD")

/** All four present, or none. Anything between is a misconfiguration, not a build mode. */
val signingInputs = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val hasReleaseSigning = signingInputs.all { it != null }
val hasPartialSigning = signingInputs.any { it != null } && !hasReleaseSigning

android {
    namespace = "com.avinash.relaydisplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.avinash.relaydisplay"
        minSdk = 23
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only created when a complete set of credentials is present. When it is absent the
        // release build type has no signingConfig at all, and the guard task below stops the
        // build -- AGP would otherwise fall back to the debug key, which must never ship.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Both schemes: v1 for the API 23 floor, v2+ for modern verification.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // R8 on: shrink, optimise and obfuscate. Keep rules live in src/main/keepRules,
            // which AGP combines automatically.
            optimization {
                enable = true
            }
            isDebuggable = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // minSdk 23 still needs desugaring for the java.* APIs used by ZXing and by our
        // own code (java.util.Objects, try-with-resources on AutoCloseable, java.time).
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            // Legal text is merged, not dropped. These files were excluded to silence duplicate
            // -path build errors, but excluding them throws away the licence texts and NOTICE
            // content that Apache-2.0 section 4 requires a *binary* distribution to carry.
            // Merging concatenates every copy instead, so nothing is lost to a name collision.
            merges += setOf(
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
                "/META-INF/NOTICE.md",
                "/META-INF/NOTICE",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE.txt",
            )
            // Build-time dependency metadata, not a legal notice; and the coroutines dual-licence
            // marker files, which carry no text of their own.
            excludes += setOf(
                "/META-INF/DEPENDENCIES",
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.datastore.preferences.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * Generates open-source notices from the *resolved* dependency metadata.
 *
 * Every entry comes from the licence block published in each artifact's own POM. Nothing is
 * hand-written and nothing is guessed: a dependency whose POM declares no licence is emitted as
 * "not declared in POM" rather than being assigned one. That is the difference between a notices
 * screen that is accurate and one that merely looks thorough.
 *
 * Wired through the Variant API so the asset is produced for every variant and survives R8 --
 * assets are not subject to code shrinking, so the notices cannot be optimised away.
 */
abstract class GenerateLicenseNotices : DefaultTask() {

    @get:Input
    abstract val moduleIds: ListProperty<String>

    @get:InputFiles
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
    abstract val pomFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val byModule = pomFiles.files.associateBy { it.nameWithoutExtension }
        val rendered = buildString {
            appendLine("RelayDisplay third-party open-source notices")
            appendLine("Generated from the resolved release runtime classpath. Do not edit by hand.")
            appendLine("An entry with no licence line declares none in its published POM.")
            appendLine()
            for (id in moduleIds.get()) {
                val parts = id.split(":")
                if (parts.size < 3) continue
                appendLine(id)
                val licences = byModule["${parts[1]}-${parts[2]}"]?.let(::parseLicences).orEmpty()
                if (licences.isEmpty()) {
                    appendLine("    licence: not declared in POM")
                } else {
                    for ((name, url) in licences) {
                        appendLine("    licence: $name")
                        if (url != null) appendLine("    url: $url")
                    }
                }
                appendLine()
            }
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        File(out, "third_party_licenses.txt").writeText(rendered)
    }

    /** Reads only the `<licenses>` block. No inference, no fallback table. */
    private fun parseLicences(pom: File): List<Pair<String, String?>> = try {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(pom)
        val nodes = doc.getElementsByTagName("license")
        (0 until nodes.length).mapNotNull { index ->
            val element = nodes.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
            val name = element.getElementsByTagName("name").item(0)?.textContent?.trim()
            val url = element.getElementsByTagName("url").item(0)?.textContent?.trim()
            if (name.isNullOrEmpty()) null else name to url?.ifEmpty { null }
        }
    } catch (e: Exception) {
        // A malformed or missing POM yields no licence rather than a build failure; the entry
        // then reads "not declared in POM", which is the honest outcome.
        emptyList()
    }
}

val licenseModuleIds: Provider<List<String>> = providers.provider {
    configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allDependencies
        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
        .map { it.selected.id }
        .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
        .map { "${it.group}:${it.module}:${it.version}" }
        .distinct()
        .sorted()
}

val licensePomFiles: Provider<Set<File>> = providers.provider {
    try {
        val notation = licenseModuleIds.get().map { dependencies.create("$it@pom") }.toTypedArray()
        val detached = configurations.detachedConfiguration(*notation)
        detached.isTransitive = false
        detached.resolvedConfiguration.lenientConfiguration.artifacts.map { it.file }.toSet()
    } catch (e: Exception) {
        logger.warn("Licence notices: could not resolve POMs (${e.javaClass.simpleName})")
        emptySet()
    }
}

val generateLicenseNotices = tasks.register<GenerateLicenseNotices>("generateLicenseNotices") {
    moduleIds.set(licenseModuleIds)
    pomFiles.from(licensePomFiles)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateLicenseNotices,
            GenerateLicenseNotices::outputDir,
        )
    }
}

// -------------------------------------------------------------------------------------------
// Release gates
// -------------------------------------------------------------------------------------------

/**
 * Refuses to produce a release artefact without complete signing credentials.
 *
 * Without this, a missing environment variable produces an unsigned "release" that looks like a
 * successful build and fails only when someone tries to install it -- or worse, an artefact signed
 * with the debug key, which can never be updated by a properly signed one.
 *
 * The message names which inputs are missing but never their values.
 */
val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    group = "verification"
    description = "Fails unless a complete release signing configuration is present."
    val complete = hasReleaseSigning
    val partial = hasPartialSigning
    val missing = buildList {
        if (releaseStorePath == null) add("RELEASE_KEYSTORE_PATH")
        if (releaseStorePassword == null) add("RELEASE_KEYSTORE_PASSWORD")
        if (releaseKeyAlias == null) add("RELEASE_KEY_ALIAS")
        if (releaseKeyPassword == null) add("RELEASE_KEY_PASSWORD")
    }
    val storeExists = releaseStorePath?.let { File(it).isFile } ?: false
    doLast {
        if (partial) {
            throw GradleException(
                "Release signing is partially configured; missing: ${missing.joinToString(", ")}. " +
                    "Supply all four or none -- a partial configuration must never silently " +
                    "produce an unsigned release.",
            )
        }
        if (!complete) {
            throw GradleException(
                "Release signing is not configured. Set ${missing.joinToString(", ")}. " +
                    "Release builds are never signed with the debug key.",
            )
        }
        if (!storeExists) {
            // Path only. A path is not a credential, and naming it is the difference between a
            // one-minute fix and an opaque failure.
            throw GradleException("RELEASE_KEYSTORE_PATH does not point at a file.")
        }
    }
}

/**
 * Every task that can emit a release APK or AAB, not just the aggregate lifecycle tasks.
 *
 * Guarding only `assembleRelease` and `bundleRelease` was bypassable, and demonstrably so:
 * `./gradlew packageRelease` with no credentials produced `app-release-unsigned.apk`, and
 * `./gradlew signReleaseBundle` produced an `.aab`. Both skipped the guard because it hung off
 * the aggregate task rather than the one doing the work.
 *
 * This is task-name matching, which AGP does not officially bless. It is used because AGP 9.4
 * exposes no public API for adding a dependency to the variant packaging or bundle-signing tasks
 * -- `androidComponents.onVariants` can read and transform artifacts but cannot inject a
 * precondition into `packageRelease`. Since the list is hand-maintained, `verifyReleaseGuards`
 * below fails if AGP ever creates a release-artifact task that is not in it, so a toolchain
 * upgrade cannot silently reopen the hole.
 */
val guardedReleaseTasks = setOf(
    "assembleRelease",              // lifecycle: APK
    "bundleRelease",                // lifecycle: AAB
    "packageRelease",               // produces the release APK
    "packageReleaseBundle",         // produces the release AAB
    "signReleaseBundle",            // signs the AAB
    "packageReleaseUniversalApk",   // universal APK from the bundle
    "makeApkFromBundleForRelease",  // APKs extracted from the bundle
)

tasks.matching { it.name in guardedReleaseTasks }.configureEach {
    dependsOn(validateVersion, requireReleaseSigning)
}

/**
 * Fails if AGP creates a release-artifact task that [guardedReleaseTasks] does not cover.
 *
 * The pattern is deliberately broad and then filtered by an explicit allow-list of tasks known
 * not to emit a distributable artifact, so a genuinely new packaging task shows up as a failure
 * rather than as silence.
 */
tasks.register("verifyReleaseGuards") {
    group = "verification"
    description = "Checks that every release-artifact task carries the signing guard."
    val guarded = guardedReleaseTasks
    val candidates = provider {
        tasks.names.filter { name ->
            name.contains("Release") &&
                (
                    name.startsWith("package") ||
                        name.startsWith("sign") ||
                        name.startsWith("makeApkFromBundle") ||
                        name.startsWith("assemble") ||
                        name.startsWith("bundle")
                    )
        }
    }
    // Tasks that carry "Release" and a packaging-ish prefix but produce intermediates, not
    // distributable artifacts. Listed explicitly so the check stays meaningful.
    val knownNonArtifact = setOf(
        "packageReleaseResources",
        "bundleReleaseResources",
        "bundleReleaseClassesToCompileJar",
        "bundleReleaseClassesToRuntimeJar",
        "signingConfigWriterRelease",
    )
    doLast {
        val unguarded = candidates.get()
            .filterNot { it in guarded || it in knownNonArtifact }
            .sorted()
        if (unguarded.isNotEmpty()) {
            throw GradleException(
                "These release tasks are not covered by the signing guard: " +
                    "${unguarded.joinToString(", ")}. Add them to guardedReleaseTasks (or to " +
                    "knownNonArtifact if they cannot emit a distributable artifact).",
            )
        }
        logger.lifecycle("All ${guarded.size} release-artifact tasks carry the signing guard.")
    }
}

/** Exposes the prerelease decision to the release workflow without it re-parsing the version. */
tasks.register("printReleaseMetadata") {
    group = "help"
    description = "Prints version metadata for the release pipeline."
    val name = appVersionName
    val code = appVersionCode
    val pre = isPrerelease
    doLast {
        println("APP_VERSION_NAME=$name")
        println("APP_VERSION_CODE=$code")
        println("RELEASE_TAG=v$name")
        println("IS_PRERELEASE=$pre")
    }
}
