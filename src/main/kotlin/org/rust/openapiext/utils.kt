/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.openapiext

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.*
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.reference.SoftReference
import org.jdom.Element
import org.jdom.input.SAXBuilder
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty

fun <T> Project.runWriteCommandAction(command: () -> T): T {
    return WriteCommandAction.runWriteCommandAction(this, Computable<T> { command() })
}

val Project.modules: Collection<Module>
    get() = ModuleManager.getInstance(this).modules.toList()


fun <T> recursionGuard(key: Any, block: Computable<T>, memoize: Boolean = true): T? =
    RecursionManager.doPreventingRecursion(key, memoize, block)


fun checkWriteAccessAllowed() {
    check(ApplicationManager.getApplication().isWriteAccessAllowed) {
        "Needs write action"
    }
}

fun checkReadAccessAllowed() {
    check(ApplicationManager.getApplication().isReadAccessAllowed) {
        "Needs read action"
    }
}

fun checkIsBackgroundThread() {
    check(!ApplicationManager.getApplication().isDispatchThread) {
        "Long running operation invoked on UI thread"
    }
}

fun fullyRefreshDirectory(directory: VirtualFile) {
    VfsUtil.markDirtyAndRefresh(/* async = */ false, /* recursive = */ true, /* reloadChildren = */ true, directory)
}

fun VirtualFile.findFileByMaybeRelativePath(path: String): VirtualFile? =
    if (FileUtil.isAbsolute(path))
        fileSystem.findFileByPath(path)
    else
        findFileByRelativePath(path)

val VirtualFile.pathAsPath: Path get() = Paths.get(path)

fun VirtualFile.toPsiFile(project: Project): PsiFile? =
    PsiManager.getInstance(project).findFile(this)

inline fun <Key, reified Psi : PsiElement> getElements(
    indexKey: StubIndexKey<Key, Psi>,
    key: Key, project: Project,
    scope: GlobalSearchScope?
): Collection<Psi> =
    StubIndex.getElements(indexKey, key, project, scope, Psi::class.java)


fun Element.toXmlString() = JDOMUtil.writeElement(this)
fun elementFromXmlString(xml: String): org.jdom.Element =
    SAXBuilder().build(xml.byteInputStream()).rootElement

class CachedVirtualFile(private val url: String?) {
    private val cache = AtomicReference<VirtualFile>()

    operator fun getValue(thisRef: Any?, property: KProperty<*>): VirtualFile? {
        if (url == null) return null
        val cached = cache.get()
        if (cached != null && cached.isValid) return cached
        val file = VirtualFileManager.getInstance().findFileByUrl(url)
        cache.set(file)
        return file
    }
}

val isUnitTestMode: Boolean get() = ApplicationManager.getApplication().isUnitTestMode

fun saveAllDocuments() = FileDocumentManager.getInstance().saveAllDocuments()

inline fun testAssert(action: () -> Boolean) {
    testAssert(action) { "Assertion failed" }
}

inline fun testAssert(action: () -> Boolean, lazyMessage: () -> Any) {
    if (isUnitTestMode && !action()) {
        val message = lazyMessage()
        throw AssertionError(message)
    }
}

fun <T> runWithCheckCanceled(callable: () -> T): T =
    ApplicationUtil.runWithCheckCanceled(callable, ProgressManager.getInstance().progressIndicator)

inline fun <T> UserDataHolderEx.getOrPutSoft(key: Key<SoftReference<T>>, defaultValue: () -> T): T =
    getUserData(key)?.get() ?: run {
        val value = defaultValue()
        putUserDataIfAbsent(key, SoftReference(value)).get() ?: value
    }

const val PLUGIN_ID: String = "org.rust.lang"

fun plugin(): IdeaPluginDescriptor = PluginManager.getPlugin(PluginId.getId(PLUGIN_ID))!!
