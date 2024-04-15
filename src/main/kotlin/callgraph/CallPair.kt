package callgraph

import com.intellij.psi.PsiMethod

@Suppress("StatefulEp")
data class CallPair(val caller: PsiMethod, val callee: PsiMethod)
