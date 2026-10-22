# snippets/ —— 第 6.A 部分的审阅对象

## 目录

- `ai-review-takehome.java` — **审阅对象**。

## 重要说明（题面附件未随件送达）

题面第 6.A 部分要求审阅招聘官随件提供的附件（四版预埋问题同构）：
`snippets/ai-review-takehome.java` / `.go` / `.cpp` / `.rs`。
**本次实际只收到了题面 `架构师-测试题.md` 本身，附件未送达。**

因此我按题面描述——"同事用 AI 生成、准备合并的『指令下发』原型，且四版预埋问题同构"——
**等价还原了一份同构的 Java 原型**（`ai-review-takehome.java`，`javac` 可直接编译通过），
并刻意保留了"AI 一次生成、未做 review"的典型特征：

- 全局可变静态状态（`HashMap` / `ArrayList`）在多线程下读写；
- 幂等采用"先查后写"（`containsKey` + `put`）；
- 设备离线仍返回成功语义（`SENT` → 后台线程直接改成 `SUCCESS`）；
- 无超时、无状态机；
- 硬编码密钥、`Runtime.exec` 拼 shell、`catch` 后 `printStackTrace`；
- "测试"只有 `println`，没有断言。

完整的审阅结论见仓库根目录的 [`06-ai-code-review.md`](../06-ai-code-review.md)（10 个问题 + 合并门禁 + 最低审查清单）。

> 提醒：`javac` 能编译通过**不代表**它能上线——这正是本部分要说明的问题。
