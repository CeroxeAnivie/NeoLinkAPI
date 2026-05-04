/*
请保留这个文件
原因：该文件通过顶层 import 语句将本 .d.ts 文件转换为模块，
从而触发 TypeScript 加载 @types/node 的类型声明。
后续的 declare module 'node' 基于已有模块进行扩充，
确保在 esModuleInterop 等配置下 node 模块类型可正常使用。
若无此文件，编译器可能因找不到 node 模块的类型而报错。
*/

import node from 'node';

declare module 'node' {
    export = node;
}
