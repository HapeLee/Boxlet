package dev.boxlet.desktop

import java.awt.Component
import java.awt.EventQueue
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File

/**
 * 桌面端「从资源管理器拖文件进来」。
 *
 * 没用 Compose 的 `dragAndDropTarget`：跨平台层对系统级文件拖拽的支持在桌面端时好时坏，
 * 而 `ComposeWindow` 本身就是 AWT 窗口，直接挂 `DropTarget` 最稳。
 */
fun installFileDropTarget(window: Component, onDrop: (List<File>) -> Unit) {
    val listener = object : DropTargetAdapter() {
        override fun dragEnter(event: DropTargetDragEvent) = acceptIfFiles(event)

        override fun dragOver(event: DropTargetDragEvent) = acceptIfFiles(event)

        override fun drop(event: DropTargetDropEvent) {
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val files = runCatching {
                (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                    ?.filterIsInstance<File>()
                    .orEmpty()
                    .filter { it.exists() }
            }.getOrDefault(emptyList())
            event.dropComplete(files.isNotEmpty())
            if (files.isNotEmpty()) {
                // 拖放回调在 AWT 事件线程，但显式抛一次，避免以后改线程模型时踩坑。
                EventQueue.invokeLater { onDrop(files) }
            }
        }

        private fun acceptIfFiles(event: DropTargetDragEvent) {
            if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                event.acceptDrag(DnDConstants.ACTION_COPY)
            } else {
                event.rejectDrag()
            }
        }
    }
    DropTarget(window, DnDConstants.ACTION_COPY, listener, true)
}
