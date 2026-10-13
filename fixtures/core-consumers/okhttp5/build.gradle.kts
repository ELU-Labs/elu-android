import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val candidateVersion = extra["eluCandidateVersion"] as String
// The app's own, newer OkHttp. The SDK's plain 4.12.0 requirement must not fail or downgrade it.
// 5.4.0 is the newest OkHttp whose Android artifact compiles against android-36 (5.5.0 needs 37).
val okHttpVersion = providers.gradleProperty("eluFixtureOkHttpVersion").orElse("5.4.0").get()

android {
    namespace = "dev.elu.fixture.okhttp5"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.elu.fixture.okhttp5"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    sourceSets["main"].kotlin.srcDir("../smoke/src/main/kotlin")
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // The SDK ships no lint checks; lint 8.13 crashes on this build's shared Kotlin script plugins.
    lint { checkReleaseBuilds = false }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:$okHttpVersion")
    implementation("dev.elu:elu-analytics:$candidateVersion")
}

apply(from = "../staged-artifact.gradle.kts")
apply(from = "../r8-keep-check.gradle.kts")

val verifyOkHttpResolution by tasks.registering {
    description = "Requires the app's OkHttp version to win resolution next to the SDK."
    doLast {
        for (name in listOf("releaseCompileClasspath", "releaseRuntimeClasspath")) {
            val result = configurations.getByName(name).incoming.resolutionResult
            val unresolved = result.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
            check(unresolved.isEmpty()) {
                "$name cannot resolve next to the SDK: " + unresolved.joinToString { "${it.attempted}: ${it.failure.message}" }
            }
            val selected = result.allComponents
                .mapNotNull { it.moduleVersion }
                .filter { it.group == "com.squareup.okhttp3" && it.name == "okhttp" }
                .map { it.version }
            check(selected == listOf(okHttpVersion)) { "$name selected OkHttp $selected, expected $okHttpVersion" }
        }
    }
}

/** Member tables of one class, enough to resolve a reference through its supertypes. */
class LinkClass(val name: String, val superName: String?, val interfaces: List<String>, val access: Int,
                val methods: Map<String, Int>, val fields: Set<String>)

fun linkClass(bytes: ByteArray): LinkClass {
    var result: LinkClass? = null
    val methods = mutableMapOf<String, Int>(); val fields = mutableSetOf<String>()
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
        var header: Triple<String, String?, List<String>>? = null; var access = 0
        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            header = Triple(name, superName, interfaces?.toList().orEmpty()); this.access = access
        }
        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            methods["$name$descriptor"] = access; return null
        }
        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
            fields += "$name:$descriptor"; return null
        }
        override fun visitEnd() {
            val (name, superName, interfaces) = header!!
            result = LinkClass(name, superName, interfaces, access, methods, fields)
        }
    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return result!!
}

fun classEntries(jar: File): Sequence<ByteArray> = sequence {
    ZipFile(jar).use { zip ->
        for (entry in zip.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }) {
            yield(zip.getInputStream(entry).use { it.readBytes() })
        }
    }
}

val verifyOkHttpLinkage by tasks.registering {
    description = "Resolves every OkHttp, Okio and Kotlin reference in the staged SDK classes against the app's runtime classpath."
    val classesView = configurations.named("releaseRuntimeClasspath").map { configuration ->
        configuration.incoming.artifactView {
            attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
        }.artifacts
    }
    doLast {
        val artifacts = classesView.get().artifacts
        val linkedPrefixes = listOf("okhttp3/", "okio/", "kotlin/")
        val pool = mutableMapOf<String, LinkClass>()
        val sdkJars = mutableListOf<File>()
        val versions = mutableListOf<String>()
        for (artifact in artifacts) {
            val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: continue
            if (id.group == "dev.elu") sdkJars += artifact.file
            if (id.group in setOf("dev.elu", "com.squareup.okhttp3", "com.squareup.okio", "org.jetbrains.kotlin")) versions += id.displayName
            classEntries(artifact.file).map(::linkClass).forEach { pool.putIfAbsent(it.name, it) }
        }
        check(sdkJars.isNotEmpty()) { "The staged SDK classes were not resolved" }
        val platform = mutableMapOf<String, LinkClass?>()
        fun lookup(name: String): LinkClass? = pool[name] ?: platform.getOrPut(name) {
            ClassLoader.getSystemResourceAsStream("$name.class")?.use { linkClass(it.readBytes()) }
        }
        fun hierarchy(start: String): Pair<List<LinkClass>, List<String>> {
            val found = mutableListOf<LinkClass>(); val unknown = mutableListOf<String>()
            val queue = ArrayDeque(listOf(start)); val seen = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if (!seen.add(name)) continue
                val type = lookup(name)
                if (type == null) { unknown += name; continue }
                found += type
                type.superName?.let(queue::add); queue.addAll(type.interfaces)
            }
            return found to unknown
        }
        fun linked(internalName: String) = linkedPrefixes.any { internalName.startsWith(it) }
        fun elementName(type: Type): String? = when (type.sort) {
            Type.ARRAY -> elementName(type.elementType)
            Type.OBJECT -> type.internalName
            else -> null
        }

        val failures = sortedSetOf<String>()
        var references = 0
        val sdkClasses = sdkJars.flatMap { classEntries(it).toList() }
        for (bytes in sdkClasses) {
            val source = ClassReader(bytes).className
            fun requireClass(name: String?) {
                if (name == null || !linked(name)) return
                references += 1
                if (lookup(name) == null) failures += "$source -> missing class $name"
            }
            fun requireTypes(descriptor: String) {
                if (descriptor.startsWith("(")) {
                    Type.getArgumentTypes(descriptor).forEach { requireClass(elementName(it)) }
                    requireClass(elementName(Type.getReturnType(descriptor)))
                } else requireClass(elementName(Type.getType(descriptor)))
            }
            fun requireMember(owner: String, name: String, descriptor: String, field: Boolean) {
                val ownerName = if (owner.startsWith("[")) elementName(Type.getType(owner)) ?: return else owner
                if (!linked(ownerName)) return
                requireTypes(descriptor)
                if (owner.startsWith("[")) return
                references += 1
                val (types, unknown) = hierarchy(ownerName)
                val key = if (field) "$name:$descriptor" else "$name$descriptor"
                val resolved = types.any { if (field) key in it.fields else key in it.methods }
                if (!resolved) failures += "$source -> missing ${if (field) "field" else "method"} $ownerName.$key" +
                    if (unknown.isEmpty()) "" else " (unresolved supertypes $unknown)"
            }
            fun requireHandle(handle: Handle) = requireMember(handle.owner, handle.name, handle.desc,
                handle.tag <= Opcodes.H_PUTSTATIC)
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                    requireClass(superName); interfaces?.forEach(::requireClass)
                    val (types, _) = hierarchy(name)
                    if (access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE) == 0 && types.drop(1).any { linked(it.name) }) {
                        // A concrete SDK subtype of a library type must implement everything that type requires.
                        val implemented = types.flatMap { type -> type.methods.filterValues { it and Opcodes.ACC_ABSTRACT == 0 }.keys }.toSet()
                        types.filter { linked(it.name) }.flatMap { type -> type.methods.filterValues { it and Opcodes.ACC_ABSTRACT != 0 }.keys }
                            .filter { it !in implemented }
                            .forEach { failures += "$name -> does not implement abstract $it" }
                    }
                }
                override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                    requireTypes(descriptor); return null
                }
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    requireTypes(descriptor)
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) =
                            requireMember(owner, name, descriptor, field = false)
                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) =
                            requireMember(owner, name, descriptor, field = true)
                        override fun visitTypeInsn(opcode: Int, type: String) =
                            requireClass(if (type.startsWith("[")) elementName(Type.getType(type)) else type)
                        override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) = requireTypes(descriptor)
                        override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) = requireClass(type)
                        override fun visitLdcInsn(value: Any?) {
                            when (value) {
                                is Type -> if (value.sort == Type.METHOD) requireTypes(value.descriptor) else requireClass(elementName(value))
                                is Handle -> requireHandle(value)
                            }
                        }
                        override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrap: Handle, vararg arguments: Any?) {
                            requireTypes(descriptor); requireHandle(bootstrap)
                            arguments.forEach { argument ->
                                when (argument) {
                                    is Handle -> requireHandle(argument)
                                    is Type -> if (argument.sort == Type.METHOD) requireTypes(argument.descriptor) else requireClass(elementName(argument))
                                }
                            }
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        val report = layout.buildDirectory.file("reports/okhttp-linkage.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText((listOf("sdkClasses=${sdkClasses.size} linkedReferences=$references failures=${failures.size}") +
            versions.sorted() + failures).joinToString("\n", postfix = "\n"))
        check(failures.isEmpty()) { "The SDK does not link against this runtime classpath:\n" + failures.joinToString("\n") }
        check(references > 0) { "No library references were checked" }
    }
}
