package com.example.examplemod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {
    public static final String MOD_ID = "maid_file_manager";
    public static final String MOD_NAME = "Maid File Manager";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    /** 导出目录名（玩家导出女仆后，.maid 文件落在这里，方便打包带走） */
    public static final String MAID_EXPORTS_DIR = "maid_exports";
    /** 导入目录名（玩家把外部的 .maid 文件放到这里后，可在游戏内导入） */
    public static final String MAID_IMPORTS_DIR = "maid_imports";
    /** .maid 文件扩展名 */
    public static final String MAID_FILE_EXT = ".maid";
    /** 当前 .maid 文件格式版本，跨版本兼容时可用于迁移 */
    public static final int MAID_FILE_FORMAT_VERSION = 2;
    /** 导入时女仆生成在玩家前方的距离（格） */
    public static final double IMPORT_SPAWN_DISTANCE = 2.5;
}
