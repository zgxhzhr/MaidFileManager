package io.github.zgxhzhr.maidfm.client;

import io.github.zgxhzhr.maidfm.data.MaidInfo;
import io.github.zgxhzhr.maidfm.network.IMaidFileNetwork;

import java.util.List;
import java.util.Set;

/**
 * {@link MaidListWidget} 对宿主界面的需求（依赖倒置）。
 *
 * <p><b>为什么需要这个接口</b>：{@code MaidListWidget} 原本是 {@code MaidFileManagerScreen} 的
 * <i>非静态</i>内部类，靠 Java 的隐式外部引用访问宿主的 4 个状态字段与 4 个方法。这让那个
 * 2400 行的类再多 421 行——而且任何想复用列表逻辑的地方都拿不到它。
 *
 * <p>把宿主需求收窄成这个接口后，Widget 与宿主之间只剩这 8 个成员的契约。
 * <b>这不是 MVVM</b>：没有 ViewModel、没有数据绑定、没有观察者，只是「依赖从整个外部类
 * 收窄成一个接口」。宿主仍然自己持有状态，Widget 只读它、或者调它的方法改它。
 *
 * <p><b>为什么不传参数</b>：8 个成员的构造器参数会比接口更难读，而且 Widget 内部在
 * {@code renderRow} 里要反复访问其中几个——每次调用传一遍参数既冗长又容易传错。
 *
 * <p>常量（行高、按钮尺寸、配色）不在这里——它们是无状态的编译期常量，
 * 直接用 {@code MaidFileManagerScreen} 上的包级可见静态字段。
 */
interface MaidListView {

    /** 普通模式下的女仆列表（服务端导出模式下为空，见 {@link #serverGroups()}）。 */
    List<MaidInfo> maidList();

    /** 服务端导出模式下按玩家分组的女仆列表；非该模式时为空。 */
    List<IMaidFileNetwork.PlayerMaidGroup> serverGroups();

    /** 当前被勾选的女仆 entityId。Widget 直接增删它，所以返回的是活集合而非副本。 */
    Set<Integer> selectedMaidIds();

    /** 当前被折叠的玩家名。同上，Widget 会增删。 */
    Set<String> collapsedPlayers();

    /** 当前是否处于「统一导出」模式（该模式下列表按玩家分组、且不提供档案入口）。 */
    boolean serverExportMode();

    /**
     * 重算「全选」按钮的三态并刷新其文字。
     *
     * <p>合成一个方法而不是让 Widget 自己「调 computeAllSelected()、赋给宿主字段、再调
     * refreshSelectAllLabel()」——那三步是宿主的内部状态机，不该由 Widget 拆开操作。
     */
    void syncSelectAllState();

    /** 请求某只女仆的档案（服务端往返，异步）。 */
    void requestMaidProfile(int entityId);

    /** 勾选/取消某只女仆，并同步全选按钮状态。 */
    void toggleRowSelection(MaidInfo info, boolean selected);
}
