package io.github.zgxhzhr.maidfm.spi;

/**
 * 迁移 provider 用来表达「可预期的业务失败」的专用异常。
 *
 * <p><b>什么时候该抛它</b>：数据内容层面的失败，比如「归档里引用的图片缺失」
 * 「目标身份已被占用」「格式版本不支持」。这类失败是<b>正常业务分支</b>，
 * 不是代码 bug。
 *
 * <p><b>什么时候不该抛它</b>：自己实现里的 bug（空指针、数组越界等）。
 * 框架对两者的处理不同：前者把 {@link #getMessage()} 原样带给玩家，
 * 后者只写日志并报「详见日志」，因为内部异常信息对玩家没有意义，
 * 而且可能泄露栈信息。
 *
 * <p><b>注意</b>：不要把自己的异常类型直接暴露给玩家——框架只取 message，
 * 所以 message 要写成玩家能读懂的句子（已本地化或英文均可）。
 */
public class MaidMigrationException extends RuntimeException {

    /** 稳定的失败分类，供日志与统计使用；不展示给玩家。 */
    private final String code;

    public MaidMigrationException(String code, String message) {
        super(message);
        this.code = code == null ? "unspecified" : code;
    }

    public MaidMigrationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? "unspecified" : code;
    }

    public String code() {
        return code;
    }
}
