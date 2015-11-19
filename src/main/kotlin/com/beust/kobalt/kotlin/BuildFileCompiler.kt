package com.beust.kobalt.kotlin

import com.beust.kobalt.Args
import com.beust.kobalt.KobaltException
import com.beust.kobalt.Plugins
import com.beust.kobalt.api.*
import com.beust.kobalt.api.annotation.Task
import com.beust.kobalt.internal.PluginInfo
import com.beust.kobalt.maven.DependencyManager
import com.beust.kobalt.misc.*
import com.beust.kobalt.plugin.kotlin.kotlinCompilePrivate
import com.google.inject.assistedinject.Assisted
import rx.subjects.PublishSubject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.Charset
import java.nio.file.Paths
import java.util.*
import java.util.jar.JarInputStream
import javax.inject.Inject

/**
 * Manage the compilation of Build.kt. There are two passes for this processing:
 * 1) Extract the repos() and plugins() statements in a separate .kt and compile it into preBuildScript.jar.
 * 2) Actually build the whole Build.kt file after adding to the classpath whatever phase 1 found (plugins, repos)
 */
public class BuildFileCompiler @Inject constructor(@Assisted("buildFiles") val buildFiles: List<BuildFile>,
        @Assisted val pluginInfo: PluginInfo, val files: KFiles, val plugins: Plugins,
        val dependencyManager: DependencyManager, val pluginProperties: PluginProperties,
        val executors: KobaltExecutors) {

    interface IFactory {
        fun create(@Assisted("buildFiles") buildFiles: List<BuildFile>, pluginInfo: PluginInfo) : BuildFileCompiler
    }

    val observable = PublishSubject.create<BuildScriptInfo>()

    private val SCRIPT_JAR = "buildScript.jar"

    fun compileBuildFiles(args: Args): List<Project> {
        //
        // Create the KobaltContext
        //
        val context = KobaltContext(args)
        context.pluginInfo = pluginInfo
        context.pluginProperties = pluginProperties
        context.dependencyManager = dependencyManager
        context.executors = executors
        Kobalt.context = context

        //
        // Find all the projects in the build file, possibly compiling them
        //
        val allProjects = findProjects(context)
        plugins.applyPlugins(context, allProjects)

        return allProjects
    }

    private fun validateProjects(projects: List<Project>) {
        val seen = hashSetOf<String>()
        projects.forEach {
            if (seen.contains(it.name)) {
                throw KobaltException("Duplicate project name: $it")
            } else {
                seen.add(it.name)
            }
        }
    }

    private fun findProjects(context: KobaltContext): List<Project> {
        val result = arrayListOf<Project>()
        buildFiles.forEach { buildFile ->
            val pluginUrls = findPlugInUrls(context, buildFile)
            val buildScriptJarFile = File(KFiles.findBuildScriptLocation(buildFile, SCRIPT_JAR))

            // If the script jar files were generated by a different version, wipe them in case the API
            // changed in-between
            buildScriptJarFile.parentFile.let { dir ->
                if (! isSameVersionFile(dir)) {
                    log(1, "Detected new installation, wiping $dir")
                    dir.listFiles().map { it.delete() }
                }
            }

            maybeCompileBuildFile(context, buildFile, buildScriptJarFile, pluginUrls)
            val buildScriptInfo = parseBuildScriptJarFile(buildScriptJarFile, pluginUrls)
            result.addAll(buildScriptInfo.projects)
        }
        return result
    }

    private fun isUpToDate(buildFile: BuildFile, jarFile: File) =
        buildFile.exists() && jarFile.exists()
                && buildFile.lastModified < jarFile.lastModified()

    private fun maybeCompileBuildFile(context: KobaltContext, buildFile: BuildFile, buildScriptJarFile: File,
            pluginUrls: List<URL>) {
        log(2, "Running build file ${buildFile.name} jar: $buildScriptJarFile")

        if (isUpToDate(buildFile, buildScriptJarFile)) {
            log(2, "Build file is up to date")
        } else {
            log(2, "Need to recompile ${buildFile.name}")

            buildScriptJarFile.delete()
            kotlinCompilePrivate {
                classpath(files.kobaltJar)
                classpath(pluginUrls.map { it.file })
                sourceFiles(listOf(buildFile.path.toFile().absolutePath))
                output = buildScriptJarFile
            }.compile(context = context)

            if (! buildScriptJarFile.exists()) {
                throw KobaltException("Could not compile ${buildFile.name}")
            }
        }
    }

    /**
     * Generate the script file with only the plugins()/repos() directives and run it. Then return
     * the URL's of all the plug-ins that were found.
     */
    private fun findPlugInUrls(context: KobaltContext, buildFile: BuildFile): List<URL> {
        val result = arrayListOf<URL>()
        val pluginCode = arrayListOf(
                "import com.beust.kobalt.*",
                "import com.beust.kobalt.api.*"
        )
        var parenCount = 0
        buildFile.path.toFile().forEachLine(Charset.defaultCharset()) { line ->
            var index = line.indexOf("plugins(")
            if (index == -1) index = line.indexOf("repos(")
            if (parenCount > 0 || index >= 0) {
                if (index == -1) index = 0
                with(line.substring(index)) {
                    parenCount += line countChar '('
                    if (parenCount > 0) {
                        pluginCode.add(line)
                    }
                    parenCount -= line countChar ')'
                }
            }
        }

        //
        // Compile and run pluginCode, which contains all the plugins() calls extracted. This
        // will add all the dynamic plugins found in this code to Plugins.dynamicPlugins
        //
        val pluginSourceFile = KFiles.createTempFile(".kt")
        pluginSourceFile.writeText(pluginCode.joinToString("\n"), Charset.defaultCharset())
        log(2, "Saved ${pluginSourceFile.absolutePath}")

        //
        // Compile to preBuildScript.jar
        //
        val buildScriptJar = KFiles.findBuildScriptLocation(buildFile, "preBuildScript.jar")
        val buildScriptJarFile = File(buildScriptJar)
        if (! isUpToDate(buildFile, File(buildScriptJar))) {
            buildScriptJarFile.parentFile.mkdirs()
            generateJarFile(context, BuildFile(Paths.get(pluginSourceFile.path), "Plugins"), buildScriptJarFile)
            generateVersionFile(buildScriptJarFile.parentFile)
        }

        //
        // Run preBuildScript.jar to initialize plugins and repos
        //
        parseBuildScriptJarFile(buildScriptJarFile, arrayListOf<URL>())

        //
        // All the plug-ins are now in Plugins.dynamicPlugins, download them if they're not already
        //
        Plugins.dynamicPlugins.forEach {
            result.add(it.jarFile.get().toURI().toURL())
        }

        return result
    }

    private val VERSION_FILE = "version.txt"

    private fun generateVersionFile(directory: File) {
        KFiles.saveFile(File(directory, VERSION_FILE), Kobalt.version)
    }

    private fun isSameVersionFile(directory: File) =
        with(File(directory, VERSION_FILE)) {
            ! exists() || (exists() && readText() == Kobalt.version)
        }

    private fun generateJarFile(context: KobaltContext, buildFile: BuildFile, buildScriptJarFile: File) {
        val kotlintDeps = dependencyManager.calculateDependencies(null, context)
        val deps: List<String> = kotlintDeps.map { it.jarFile.get().absolutePath }
        kotlinCompilePrivate {
            classpath(files.kobaltJar)
            classpath(deps)
            sourceFiles(buildFile.path.toFile().absolutePath)
            output = File(buildScriptJarFile.absolutePath)
        }.compile(context = context)
    }

    class BuildScriptInfo(val projects: List<Project>)

    private fun parseBuildScriptJarFile(buildScriptJarFile: File, urls: List<URL>) : BuildScriptInfo {
        val projects = arrayListOf<Project>()
        var stream : InputStream? = null
        val allUrls = (urls + arrayOf(
                buildScriptJarFile.toURI().toURL()) + File(files.kobaltJar).toURI().toURL())
            .toTypedArray()
        val classLoader = URLClassLoader(allUrls)

        //
        // Install all the plugins
        //
        plugins.installPlugins(Plugins.dynamicPlugins, classLoader)

        try {
            stream = JarInputStream(FileInputStream(buildScriptJarFile))
            var entry = stream.nextJarEntry

            val classes = hashSetOf<Class<*>>()
            while (entry != null) {
                val name = entry.name;
                if (name.endsWith(".class")) {
                    val className = name.substring(0, name.length - 6).replace("/", ".")
                    var cl : Class<*>? = classLoader.loadClass(className)
                    if (cl != null) {
                        classes.add(cl)
                    } else {
                        throw KobaltException("Couldn't instantiate $className")
                    }
                }
                entry = stream.nextJarEntry;
            }

            // Invoke all the "val" found on the _DefaultPackage class (the Build.kt file)
            classes.filter { cls ->
                cls.name != "_DefaultPackage"
            }.forEach { cls ->
                cls.methods.forEach { method ->
                    // Invoke vals and see if they return a Project
                    if (method.name.startsWith("get") && Modifier.isStatic(method.modifiers)) {
                        try {
                            val r = method.invoke(null)
                            if (r is Project) {
                                log(2, "Found project $r in class $cls")
                                projects.add(r)
                            }
                        } catch(ex: Throwable) {
                            throw ex.cause ?: KobaltException(ex)
                        }
                    } else {
                        val taskAnnotation = method.getAnnotation(Task::class.java)
                        if (taskAnnotation != null) {
                            Plugins.defaultPlugin.methodTasks.add(IPlugin.MethodTask(method, taskAnnotation))
                        }

                    }}
            }
        } finally {
            stream?.close()
        }

        validateProjects(projects)

        //
        // Now that the build file has run, fetch all the project contributors, grab the projects from them and sort
        // them topologically
        //
        Topological<Project>().let { topologicalProjects ->
            val all = hashSetOf<Project>()
            pluginInfo.projectContributors.forEach { contributor ->
                val descriptions = contributor.projects()
                descriptions.forEach { pd ->
                    all.add(pd.project)
                    pd.dependsOn.forEach { dependsOn ->
                        topologicalProjects.addEdge(pd.project, dependsOn)
                        all.add(dependsOn)
                    }
                }
            }
            val result = BuildScriptInfo(topologicalProjects.sort(ArrayList(all)))

            // Notify possible listeners (e.g. KobaltServer) we now have all the projects
            observable.onNext(result)
            return result
        }
    }
}
