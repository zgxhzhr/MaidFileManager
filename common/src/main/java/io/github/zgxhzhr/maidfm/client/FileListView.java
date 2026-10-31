package io.github.zgxhzhr.maidfm.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Set;

/**
 * {@link FileListWidget} 对宿主界面的需求（依赖倒置，与 {@link MaidListView} 同一套模式）。
 *
 * <p>导入 Tab 的文件列表原本是 {@code MaidFileManagerScreen} 的非静态内部类，
 * 靠隐式外部引用访问宿主的两个状态字段与一个方法，并直接 {@code new MaidProfileScreen(this, ...)}
 * 把宿主实例当父界面传出去。移出后这些需求收窄成下面 5 个成员。
 *
 * <p><b>为什么暴露 {@link #hostScreen()} 而不是 {@code MaidFileManagerScreen}</b>：
 * {@code MaidProfileScreen} 的构造器只要求 {@code Screen}，所以这里没必要把接口绑死在
 * 具体宿主类型上——将来若有别的界面复用这个列表，只要它实现本接口即可。
 *
 * <p><b>这不是 MVVM</b>：宿主持有全部状态，Widget 只读它或者调它的方法改它。
 */
interface FileListView {

    /** 导入 Tab 当前列出的文件名（本地 maid_imports 目录的扫描结果）。 */
    List<String> fileList();

    /**
     * 当前被勾选的文件名。
     *
     * <p>返回活集合而非副本：Widget 的点击处理会直接增删它。
     */
    Set<String> selectedImportFileNames();

    /** 底部反馈文案入口（例如「档案未随文件保存」这类提示）。 */
    void setFeedback(Component msg);

    /** 宿主界面本身，用于把档案界面挂到它下面。 */
    Screen hostScreen();

    /**
     * 切换某个文件的勾选状态，并同步「全选」按钮。
     *
     * <p>合成一个方法而不是让 Widget 直接增删集合：全选按钮的三态是宿主的内部状态机，
     * Widget 不该拆开操作（与 MaidListView#syncSelectAllState 同一考虑）。
     */
    void toggleFileRowSelection(String fileName, boolean selected);
}
