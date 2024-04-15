package callgraph

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressIndicatorProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameterList
import java.io.File


class CanvasBuilder {
    private val fileModifiedTimeCache = mutableMapOf<PsiFile, Long>()

    private var progressIndicator: ProgressIndicator? = null
    private var callPairSetCache = emptySet<CallPair>()
    var LOGGER = com.intellij.openapi.diagnostic.Logger.getInstance(CanvasBuilder::class.java)
    fun build(canvasConfig: CanvasConfig) {
        // cancel existing progress if any
        this.progressIndicator?.cancel()
        this.progressIndicator = ProgressIndicatorProvider.getGlobalProgressIndicator()

        LOGGER.debug("build call 1")
        var workSpace = "/Users/cocui/toolchain/PythonProject/src/neo4j/data"
        var allCallPairFile = File(workSpace+"/allCallPairFile.txt")

        if (allCallPairFile.exists()) {
            allCallPairFile.delete()
        } else {
            allCallPairFile.createNewFile()
        }
        // build a dependency snapshot for the entire code base
        val dependencies = getCallPairSet(canvasConfig, this.callPairSetCache, this.fileModifiedTimeCache)
        allCallPairFile.appendText("all allCallPairFile ---> :\n")
        var methodAbsInfo :StringBuilder = StringBuilder()
        for ((index, dependency) in dependencies.withIndex()) {
            methodAbsInfo.append("Caller: "+getMethodInfo(dependency.caller))
            methodAbsInfo.append("\n")
            methodAbsInfo.append("Callee: "+getMethodInfo(dependency.callee))
            methodAbsInfo.append("\n")
            methodAbsInfo.append("\n")
            if(index % 30 == 29){
                allCallPairFile.appendText(methodAbsInfo.toString())
                methodAbsInfo.clear()
            }
        }
        allCallPairFile.appendText("\n")
        allCallPairFile.appendText("all allCallPairFile <--- :")




    }

    private fun getMethodInfo(method: PsiMethod): String {
        var method_abs_info :StringBuilder= StringBuilder()
        method_abs_info.append(method.containingClass.toString())
        method_abs_info.append( "::" + method.name)
        method_abs_info.append("(")
        // 获取参数列表
        val parameterList: PsiParameterList = method.getParameterList()

        var parameter_info :StringBuilder= StringBuilder()
        for (parameter in parameterList.parameters) {
            val parameterName = parameter.name
            val parameterType = parameter.type.presentableText
            parameter_info.append(parameterType + " " + parameterName + ",")
        }
        if (parameter_info.length > 0){
            parameter_info.deleteCharAt(parameter_info.length-1)
        }
        method_abs_info.append(parameter_info)
        method_abs_info.append(")")
        return method_abs_info.toString();
    }

    private fun getCallPairSet(
            canvasConfig: CanvasConfig,
            dependenciesCache: Set<CallPair>,
            fileModifiedTimeCache: Map<PsiFile, Long>
    ): Set<CallPair> {
        val allFiles = Utils.getAllSourceCodeFiles(canvasConfig.project)
        val newFiles = allFiles.filter { !fileModifiedTimeCache.containsKey(it) }
        val changedFiles = allFiles
                .filter { fileModifiedTimeCache.containsKey(it) && fileModifiedTimeCache[it] != it.modificationStamp }
                .toSet()
        val validDependencies = dependenciesCache
                .filter {
                    !changedFiles.contains(it.caller.containingFile) && !changedFiles.contains(it.callee.containingFile)
                }
                .toSet()
        val invalidFiles = dependenciesCache
                .filter { !validDependencies.contains(it) }
                .flatMap { listOf(it.caller.containingFile, it.callee.containingFile) }
                .toSet()
        val filesToParse = newFiles.union(invalidFiles)
        val methodsToParse = Utils.getMethodsFromFiles(filesToParse)

        // parse method dependencies
        canvasConfig.callGraphToolWindow.resetProgressBar(methodsToParse.size)
        val newDependencies = methodsToParse
                .flatMap {
                    canvasConfig.callGraphToolWindow.incrementProgressBar()
                    Utils.getDependenciesFromMethod(it)
                }
                .toSet()
        val dependencies = validDependencies.union(newDependencies)

        // cache the dependencies for next use
        this.callPairSetCache = dependencies
        this.fileModifiedTimeCache.clear()
        this.fileModifiedTimeCache.putAll(allFiles.associateBy({ it }, { it.modificationStamp }))

        return dependencies
    }
}
