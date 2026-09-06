package com.luminaauth;

/** Shizuku UserService 命令执行接口（运行于 shell 权限进程） */
interface IShell {
    String exec(String command);
}
