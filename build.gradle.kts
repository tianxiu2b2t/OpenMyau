import org.apache.commons.lang3.SystemUtils
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    idea
    java
    id("gg.essential.loom") version "0.10.0.+"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

// ========== 常量（从 gradle.properties 读取）==========
val baseGroup: String by project
val mcVersion: String by project
val version: String by project
val modid: String by project
val jarName: String by project
val transformerFile = file("src/main/resources/accesstransformer.cfg")

// ========== Java 工具链 ==========
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

// ========== Loom 配置 ==========
loom {
    log4jConfigs.from(file("log4j2.xml"))

    launchConfigs {
        "client" {
            property("mixin.debug", "true")
            arg("--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
        }
    }

    runConfigs {
        "client" {
            if (SystemUtils.IS_OS_MAC_OSX) {
                vmArgs.remove("-XstartOnFirstThread")
            }
        }
        remove(getByName("server"))
    }

    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
        mixinConfig("mixins.$modid.json")
        if (transformerFile.exists()) {
            println("Installing access transformer")
            accessTransformer(transformerFile)
        }
    }

    mixin {
        defaultRefmapName.set("mixins.$modid.refmap.json")
    }
}

// ========== 资源目录设置 ==========
sourceSets.main {
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
}

// ========== 仓库 ==========
repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
}

// ========== 依赖配置（关键：shade 用于打包）==========
val shade: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")

    // Mixin - 使用 shade 确保被打包进 -dev.jar，并最终进入重映射后的正式版
    shade("org.spongepowered:mixin:0.7.11-SNAPSHOT") {
        isTransitive = false
    }
    annotationProcessor("org.spongepowered:mixin:0.8.5-SNAPSHOT")

    // DevAuth (仅运行时)
    runtimeOnly("me.djtheredstoner:DevAuth-forge-legacy:1.2.1")
}

// ========== 任务配置 ==========
tasks {
    // 处理资源文件中的变量替换
    processResources {
        inputs.property("version", project.version)
        inputs.property("mcversion", mcVersion)
        inputs.property("modid", modid)
        inputs.property("basePackage", baseGroup)

        filesMatching(listOf("mcmod.info", "mixins.$modid.json", "version.json")) {
            expand(inputs.properties)
        }

        // 将 accesstransformer.cfg 重命名为 META-INF/${modid}_at.cfg
        rename("accesstransformer.cfg", "META-INF/${modid}_at.cfg")
    }

    // 配置 shadowJar → 产生 -dev.jar（包含依赖，未重映射）
    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("dev")                     // 输出文件名后缀 -dev
        destinationDirectory.set(layout.buildDirectory.dir("libs"))  // 直接放入 build/libs
        configurations = listOf(shade)                   // 只打包 shade 中的依赖
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        // 如果需要 relocation，在此处添加，例如：
        // relocate("org.spongepowered.asm", "$baseGroup.deps.asm")

        // 设置清单属性（这些属性在最终 remapJar 中会被保留）
        manifest.attributes(
            mapOf(
                "FMLCorePluginContainsFMLMod" to "true",
                "ForceLoadAsMod" to "true",
                "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
                "MixinConfigs" to "mixins.$modid.json",
                "FMLAT" to "${modid}_at.cfg"
            )
        )
    }

    // 配置 remapJar → 基于 shadowJar 输出最终正式版（无后缀，已重映射）
    remapJar {
        //inputFile.set(shadowJar.get().archiveFile)       // 输入为 -dev.jar
        archiveClassifier.set("")                        // 无后缀
        dependsOn(shadowJar)

        // 确保清单属性被复制（remapJar 会保留 input 的 manifest）
        // 如果需要额外添加属性，可以在这里设置 manifest
        manifest.attributes(
            mapOf(
                "FMLCorePluginContainsFMLMod" to "true",
                "ForceLoadAsMod" to "true",
                "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
                "MixinConfigs" to "mixins.$modid.json",
                "FMLAT" to "${modid}_at.cfg"
            )
        )
    }

    // 禁用原 jar 任务，避免生成不必要的中间文件
    jar {
        enabled = false
    }

    // 确保 assemble 依赖于 remapJar（生成最终 jar）
    assemble {
        dependsOn(remapJar)
    }
}

// 可选：确保 Java 编译使用 UTF-8 编码
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
