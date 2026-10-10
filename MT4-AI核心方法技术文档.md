# MT4-AI 核心交易方法技术文档

> 目的：从 Python 项目 `C:\Users\笨猫\Desktop\MT4-Ai` 中提取**核心交易能力**（开仓、平仓、设置 TP/SL、K 线行情、账户余额），
> 形成一份可对照实现的技术规格，作为后续 Java 端实现的唯一依据。
>
> 提取源码：
> - [mt5_executor.py](file:///C:/Users/%E7%AC%A8%E7%8C%AB/Desktop/MT4-Ai/src/trading/mt5_executor.py) —— 交易执行主通道
> - [server.py](file:///C:/Users/%E7%AC%A8%E7%8C%AB/Desktop/MT4-Ai/src/web/server.py) —— K 线 / 平仓 / 熔断接口
> - [models.py](file:///C:/Users/%E7%AC%A8%E7%8C%AB/Desktop/MT4-Ai/src/market_data/models.py) —— 数据结构
>
> 文档版本：v1.0 ｜ 生成日期：2026-10-10

---

## 0. 一句话结论（先看这里）

这套项目**主线不是 HTTP，也不是桥接**，而是 **Python 官方库 `MetaTrader5` 直接操作本机已登录的 MT5 终端**：

```python
import MetaTrader5 as mt5
mt5.initialize(login=..., password=..., server=...)   # 连接本机 MT5 终端
mt5.order_send({...})                                  # 开仓/平仓/改SLTP
mt5.copy_rates_from_pos(symbol, tf, 0, count)          # 取 K 线
mt5.account_info()                                     # 取余额
```

因此核心方法一共只有 **9 个**，全部是 `mt5` 库方法的薄封装。下文按 5 类整理（开仓 / 平仓 / TP-SL / 行情K线 / 账户资金）。

---

## 1. 系统概览

### 1.1 三条通道（经验证后的结论）

| 通道 | 实现文件 | 依赖 | 是否需要 MT5 终端 | 说明 |
| --- | --- | --- | --- | --- |
| **① 官方 Python 库（主线，本系统实际在用）** | `src/trading/mt5_executor.py` | `MetaTrader5` PyPI 包 | ✅ 必须（同机已登录） | `import MetaTrader5 as mt5`，全部交易/行情/账户都走它 |
| ② MT4 EA 文件桥（备选，未启用） | `src/trading/mt4_bridge.py` + `EA_PythonBridge.mq4` | MT4 终端 + EA | ✅ 必须 | 通过 `cmd.txt` / `rsp.txt` 文件 IPC |
| ③ MT4 DLL 直连（实验性，未启用） | `src/trading/mt4api_client.py` + `lib/mt4api.dll` | 第三方 DLL + 授权 | ❌ 不需要 | `ctypes` 封装 `MT4API_*` 函数 |

> **重要**：项目目录叫 `MT4-Ai`，但主线通道的库是 **MT5**（`MetaTrader5`），名称与实现不一致，后续文档统一以 MT5 为准。

### 1.2 典型调用链（一次开仓）

```
策略引擎 on_tick
  → MT5Executor.buy_market(symbol, volume, sl, tp)
    → 线程池执行 _buy_market()（库调用是阻塞的，必须扔进线程池）
      → _check_rate_limit()           # 下单限流 + 指数退避
      → _ensure_symbol(symbol)        # mt5.symbol_select 选中品种
      → mt5.symbol_info_tick(symbol)  # 取当前卖价 ask
      → _get_balance()                # 下单前余额
      → mt5.order_send(request)       # ★ 真实下单
      → _get_balance()                # 下单后余额 → 记账到 memory.json
      → _notify_order(...)            # 邮件通知
```

### 1.3 全局技术约定

- **阻塞 → 异步**：`mt5.*` 全是同步阻塞调用，统一经 `ThreadPoolExecutor(max_workers=2)` + `loop.run_in_executor` 转成 `async`。
- **单例**：`MT5Executor` 是进程内单例（`get_instance()`），避免重复 `mt5.initialize()`。
- **成交账本**：所有开/平仓写入 `data/memory.json`（`Memory` 类），记录余额快照与盈亏。
- **限流退避**：连续失败时下单间隔指数增长 `5s → 10s → 20s → … → 300s`。

---

## 2. 核心方法总览

| # | 能力 | Python 方法（`MT5Executor`） | 底层 `mt5` API |
| --- | --- | --- | --- |
| 1 | 市价开仓（买） | `buy_market(symbol, volume, sl, tp)` | `mt5.order_send(action=TRADE_ACTION_DEAL, type=ORDER_TYPE_BUY)` |
| 2 | 市价开仓（卖） | `sell_market(symbol, volume, sl, tp)` | `mt5.order_send(..., type=ORDER_TYPE_SELL)` |
| 3 | 挂单（限价） | `place_limit_order(symbol, price, volume)` | `mt5.order_send(action=TRADE_ACTION_PENDING)` |
| 4 | 按 ticket 平仓 | `_close_position_by_ticket(ticket)` | `mt5.order_send(..., position=ticket, 反向 type)` |
| 5 | 一键平所有仓 | `close_all_positions(symbol)` | 遍历 `mt5.positions_get()` 逐个反向平 |
| 6 | 撤所有挂单 | `cancel_all_pending(symbol)` | `mt5.order_send(action=TRADE_ACTION_REMOVE)` |
| 7 | 设置/修改 SL（TP 同理） | `modify_sl(symbol, position_ticket, sl)` | `mt5.order_send(action=TRADE_ACTION_SLTP)` |
| 8 | 实时报价 Tick | `get_quote(symbol)` / `start_quote_stream(...)` | `mt5.symbol_info_tick(symbol)` |
| 9 | K 线（每根 OHLCV） | `copy_rates_from_pos` 用法（见 §6） | `mt5.copy_rates_from_pos(symbol, tf, 0, count)` |
| 10 | 账户资金（还剩多少 U） | `get_account_info()` | `mt5.account_info()` |
| 11 | 当前持仓列表 | `get_positions(symbol)` | `mt5.positions_get(...)` |

---

## 3. 开仓

### 3.1 市价买入 `buy_market`

```python
# 异步入口（对外）
async def buy_market(self, symbol: str, volume: float = 0.01,
                     sl: float = 0.0, tp: float = 0.0) -> dict

# 同步实现（线程池内执行）
def _buy_market(self, symbol, volume, sl=0.0, tp=0.0) -> dict
```

**参数**

| 参数 | 类型 | 默认 | 含义 |
| --- | --- | --- | --- |
| `symbol` | str | — | 品种，如 `XAUUSD` |
| `volume` | float | 0.01 | 手数 |
| `sl` | float | 0.0 | 止损价（绝对价格，0 = 不设） |
| `tp` | float | 0.0 | 止盈价（绝对价格，0 = 不设） |

**核心请求体（关键，Java 端要对齐语义）**

```python
request = {
    "action":       mt5.TRADE_ACTION_DEAL,     # 市价即时成交
    "symbol":       symbol,
    "volume":       volume,
    "type":         mt5.ORDER_TYPE_BUY,        # 买
    "price":        tick.ask,                  # 买入用卖价 Ask
    "deviation":    20,                        # 允许滑点 20 点
    "magic":        0,
    "comment":      "python buy",
    "type_time":    mt5.ORDER_TIME_GTC,        # 一直有效
    "type_filling": self._get_filling(symbol), # ★ 自动探测 FOK/IOC/RETURN
}
if sl > 0: request["sl"] = sl
if tp > 0: request["tp"] = tp
result = mt5.order_send(request)
```

**执行流程**
1. `_check_rate_limit()` —— 限流检查，超限直接返回 `{"success": False, "error": "Rate limited..."}`。
2. `_ensure_symbol(symbol)` —— `mt5.symbol_select(symbol, True)`，把品种加入市场报价。
3. `mt5.symbol_info_tick(symbol)` —— 取报价；为 `None` 说明品种不可用，返回失败。
4. 记录**下单前余额** `before`。
5. `mt5.order_send(request)` → `_format_result()` 归一化结果。
6. 成功后：记录**下单后余额** `after`、把成交写入 `memory.json`、发邮件、**重置**退避计数。
7. 失败后：**累加**退避计数（`_consecutive_failures += 1`）。

**返回值**

```python
{
  "success": bool,
  "retcode": int,      # mt5 返回码，TRADE_RETCODE_DONE(10009) 为成功
  "order":   int,      # 订单号
  "deal":    int,      # 成交号
  "volume":  float,
  "price":   float,    # 成交价
  "comment": str,
  "error":   str,      # 仅失败时存在
}
```

### 3.2 市价卖出 `sell_market`

与 `buy_market` **完全对称**，差异只有三处：

| 项 | 买入 | 卖出 |
| --- | --- | --- |
| `type` | `ORDER_TYPE_BUY` | `ORDER_TYPE_SELL` |
| 成交价 | `tick.ask`（卖价） | `tick.bid`（买价） |
| `comment` | `"python buy"` | `"python sell"` |

### 3.3 挂单 `place_limit_order`

```python
async def place_limit_order(self, symbol: str, price: float, volume: float = 0.01) -> dict
```

**方向自动判定**（按当前报价）：
- `price < ask 且 price < bid` → `ORDER_TYPE_BUY_LIMIT`（低位挂多）
- `price > bid 且 price > ask` → `ORDER_TYPE_SELL_LIMIT`（高位挂空）
- 否则（价格夹在买卖价之间）→ 直接返回失败：`"价格 X 不在有效区间 bid~ask"`

**请求体**：`action = TRADE_ACTION_PENDING`，`type_filling = ORDER_FILLING_RETURN`（挂单必须用 RETURN）。

---

## 4. 平仓

### 4.1 按 ticket 平单个仓位 `_close_position_by_ticket`

> 源码位于 [server.py](file:///C:/Users/%E7%AC%A8%E7%8C%AB/Desktop/MT4-Ai/src/web/server.py#L864-L898)（Web 手动平仓 / 一键平盈利仓都调它）

```python
def _close_position_by_ticket(ticket: int) -> dict
```

**流程**（平仓的本质 = 开一张**反向**的市价单，并绑定 `position=ticket`）：

```python
pos = mt5.positions_get(ticket=ticket)      # 1. 按 ticket 找到持仓
p = pos[0]
direction  = "BUY" if p.type == ORDER_TYPE_BUY else "SELL"
tick = mt5.symbol_info_tick(p.symbol)
price = tick.bid if p.type == BUY else tick.ask       # 2. 平多单卖在 bid；平空单买在 ask
close_type = ORDER_TYPE_SELL if p.type == BUY else ORDER_TYPE_BUY   # 3. 反向

req = {
    "action":   mt5.TRADE_ACTION_DEAL,
    "symbol":   p.symbol,
    "volume":   p.volume,      # 全量平
    "type":     close_type,    # 反向
    "position": p.ticket,      # ★ 绑定原持仓 ticket
    "price":    price,
    "deviation": 20,
    "magic":    p.magic,
    "comment":  "manual_close",
    "type_time": mt5.ORDER_TIME_GTC,
    "type_filling": _get_web_filling(p.symbol),
}
result = mt5.order_send(req)
ok = result is not None and result.retcode == mt5.TRADE_RETCODE_DONE
```

**返回**

```python
{"ok": bool, "direction": "BUY"/"SELL", "profit": float, "hold_seconds": float, "ticket": int}
```

### 4.2 一键平所有仓 `close_all_positions`

```python
async def close_all_positions(self, symbol: str = "") -> list[dict]
```

遍历 `mt5.positions_get()`（`symbol` 为空则全部），对每个持仓执行与 §4.1 相同的「反向市价单」逻辑，逐笔记录盈亏、发平仓邮件，最后 `memory.save()`。返回每个仓位的成交结果列表。

> 相关接口：
> - `POST /api/killswitch` —— 熔断：平所有持仓 + 撤所有挂单
> - `POST /api/positions/close-profitable` —— 只平浮盈 > 0 的仓
> - `POST /api/positions/{ticket}/close` —— 平指定 ticket

---

## 5. 设置 / 修改 SL、TP

### 5.1 开仓时直接带 TP/SL
见 §3.1：`request["sl"]` / `request["tp"]` 传**绝对价格**即可，MT5 服务端会自动挂上。
约定：`sl > 0` / `tp > 0` 才写入，`0` 表示不设置。

### 5.2 持仓后单独修改 SL `modify_sl`

```python
async def modify_sl(self, symbol: str, position_ticket: int, sl: float) -> dict
```

**底层请求体**

```python
request = {
    "action":   mt5.TRADE_ACTION_SLTP,   # ★ 改止损止盈专用动作
    "symbol":   symbol,
    "position": position_ticket,          # 目标持仓 ticket
    "sl":       sl,                       # 新止损价
    "tp":       0,                        # ★ 当前实现把 tp 写死为 0
}
```

**返回**

```python
{"success": bool, "comment": str, "retcode": int}
```

> ⚠️ **移植注意（重要缺陷）**：现实现 `modify_sl` 的 `tp` **硬编码为 0**。
> 而 MT5 的 `TRADE_ACTION_SLTP` 语义是**整体覆盖**——传 `tp=0` 会**清掉原有止盈**。
> Java 端实现「改 SL 保留 TP」时，必须：
> 1. 先 `positions_get(ticket)` 读出原 `sl`/`tp`；
> 2. 只替换要改的那个字段，两个值一起回传。
> 建议 Java 端提供 `modifySlTp(symbol, ticket, sl, tp)` 统一入口，避免踩此坑。

### 5.3 撤单 `cancel_all_pending`

```python
async def cancel_all_pending(self, symbol: str = "") -> list[dict]
# mt5.orders_get(symbol) → 对每个挂单 order_send({"action": TRADE_ACTION_REMOVE, "order": o.ticket})
```

---

## 6. 行情：实时 Tick 与 K 线

### 6.1 实时报价 `get_quote`

```python
async def get_quote(self, symbol: str) -> dict | None
# 内部：mt5.symbol_select(symbol, True) → mt5.symbol_info_tick(symbol) → mt5.symbol_info(symbol)

返回：{"symbol","bid","ask","spread","digits","time"}
```

### 6.2 报价轮询流 `start_quote_stream`

```python
async def start_quote_stream(self, symbols: list[str], callback, *, interval: float = 1.0)
# 每隔 interval 秒轮询 symbols，把 tick 包成 Tick 对象回调给策略
```

**Tick 数据结构**（[models.py](file:///C:/Users/%E7%AC%A8%E7%8C%AB/Desktop/MT4-Ai/src/market_data/models.py#L5-L17)）

```python
@dataclass
class Tick:
    symbol: str; bid: float; ask: float
    last: float | None = None; volume: int = 0; timestamp: datetime | None = None
    # spread = ask - bid
```

### 6.3 K 线（每根 OHLCV）—— 你要的「每根 K 线」

> 源码用法见 [server.py `/api/klines`](file:///C:/Users/%E7%AC%A8%E7%8C%AB/Desktop/MT4-Ai/src/web/server.py#L1188-L1212)

```python
rates = mt5.copy_rates_from_pos(symbol, timeframe, 0, count)
# 参数：(品种, 周期, 起始位置=0 表示从最新一根起, 根数)
```

**周期枚举（`timeframe`）**

| 字符串 | mt5 常量 | 分钟 |
| --- | --- | --- |
| `M1` | `mt5.TIMEFRAME_M1` | 1 |
| `M5` | `mt5.TIMEFRAME_M5` | 5 |
| `M15` | `mt5.TIMEFRAME_M15` | 15 |
| `M30` | `mt5.TIMEFRAME_M30` | 30 |
| `H1` | `mt5.TIMEFRAME_H1` | 60 |
| `H4` | `mt5.TIMEFRAME_H4` | 240 |
| `D1` | `mt5.TIMEFRAME_D1` | 1440 |

**返回结构**：`numpy` 结构化数组，每条记录为 `(time, open, high, low, close, tick_volume, spread, real_volume)`。
项目对外归一化为：

```python
[{"time": int, "open": float, "high": float, "low": float, "close": float, "volume": int}, ...]
```

对应 `KlineBar` 数据类：

```python
@dataclass
class KlineBar:
    symbol: str; timeframe: str          # "1m"/"5m"/"1h"...
    open: float; high: float; low: float; close: float
    volume: int; timestamp: datetime
```

> 说明：本项目 K 线来自 **MT5 终端**（`copy_rates_from_pos`），不是任何外部 HTTP 行情源。
> 备选行情源 `AllTick` / `Infoway`（HTTP/WS）仅用于旁路，不参与交易决策主链。

---

## 7. 账户资金（「还剩多少 U」）

### 7.1 `get_account_info`

```python
async def get_account_info(self) -> dict | None
# 内部：mt5.account_info()
```

**返回字段**

| 字段 | 含义 | 对应中文 |
| --- | --- | --- |
| `balance` | 结余（已结算） | **本金余额** |
| `equity` | 净值 = balance ± 浮动盈亏 | **账户净值** |
| `margin` | 已用保证金 | 占用保证金 |
| `free_margin` | 可用保证金 | **还能用来开仓的钱** |
| `profit` | 浮动盈亏 | 当前浮盈浮亏 |
| `leverage` | 杠杆 | 杠杆倍数 |
| `currency` | 币种 | 如 `USD`（U） |
| `server` | 服务器名 | 券商服务器 |
| `name` | 账户名 | 开户人 |
| `login` | 账号 | 登录号 |

> **「还剩多少 U」的准确对应**：
> - 指**可动用的钱** → `free_margin`（可用保证金）
> - 指**账户总值** → `equity`（净值）
> 建议 Java 端两个都返回，由调用方决定。

### 7.2 持仓查询 `get_positions`

```python
async def get_positions(self, symbol: str = "") -> list[dict]
# mt5.positions_get(symbol=symbol) 或 mt5.positions_get()
```

每项返回：`ticket, symbol, type("buy"/"sell"), volume, open_price, current_price, sl, tp, profit, swap, magic, comment, open_time`。

---

## 8. 关键实现细节（移植必须保留的行为）

| # | 细节 | 位置 | 为什么重要 |
| --- | --- | --- | --- |
| 1 | **filling mode 自动探测** | `_get_filling(symbol)` | 不同券商支持 FOK/IOC/RETURN 不同，写错直接下单失败。按 `symbol_info.filling_mode` 位掩码选：bit1→IOC，bit0→FOK，否则 RETURN；结果缓存 |
| 2 | **下单限流 + 指数退避** | `_check_rate_limit()` | 连续失败时 `5s×(2^n)` 递增至 300s，避免疯狂重试被券商封 |
| 3 | **线程池执行** | `_run_in_executor` | `mt5.*` 阻塞调用必须离开事件循环；单例固定 2 线程 |
| 4 | **余额快照记账** | `Memory.record_trade / snapshot_balance` | 用「下单前后 balance 差」估算本笔盈亏，落 `memory.json`（保留最近 200 笔 / 500 快照） |
| 5 | **高风险回报接单**（可读性参考） | `_format_result` | 统一把 `retcode == TRADE_RETCODE_DONE(10009)` 视为成功 |
| 6 | **时区** | 全局 | `open_time` 等统一按 UTC 处理 |

---

## 9. Java 端落地映射方案

> ⚠️ **前置风险（必须先确认，见 §10）**：官方**没有 Java 版 `MetaTrader5` 库**。
> Python 的 `MetaTrader5` 是通过**本机 MT5 终端进程**通信的；而「MT5 官方 Web API」是**券商/服务器端**产品，
> 需要 **Web API 服务器地址 + manager 级账号**，个人零售交易账户通常**没有**此权限。
> 若券商不开放 Web API，则本方案（纯 Java HTTP）无法使用，需退回到「Java → 本地 Python 服务」方案。

### 9.1 三方案对照（供你复核）

| 方案 | 形态 | 前提 | 语义保真度 | 备注 |
| --- | --- | --- | --- | --- |
| A. Java → 本地 Python 服务 | Java 走本地 HTTP/JSON 调常驻 Python 服务，Python 内仍用 `MetaTrader5` 库 | Python + MT5 终端 + 账号密码服务器 | **100%** | 与原项目完全一致 |
| **B. MT5 官方 Web API（你的选择）** | 纯 Java，`java.net.http.HttpClient` 直发 HTTP+JSON | 券商开放 Web API + manager 账号 | 约 80%（字段/语义有差异） | 无需终端与 Python |
| C. JNA 封装 `mt4api.dll` | 纯 Java JNA 调用 `lib/mt4api.dll` | mt4api 授权 + 账号/密码/服务器IP端口 | 高（MT4） | 免终端，但 DLL 为第三方 |

### 9.2 方案 B（Web API）方法映射表

| 能力 | Python（`mt5.*`） | MT5 Web API（HTTP，Java 端将实现） |
| --- | --- | --- |
| 连接/认证 | `mt5.initialize(login, password, server)` | `POST /api/auth/start` → `POST /api/auth/answer`（两次挑战-应答哈希） |
| 账户资金 | `mt5.account_info()` | `POST /api/user/account/get` |
| 实时报价 | `mt5.symbol_info_tick()` | `POST /api/quote/get`（symbol） |
| **K 线** | `mt5.copy_rates_from_pos(sym, tf, 0, n)` | `POST /api/chart/get`（symbol + timeframe + start + count） |
| **开仓** | `mt5.order_send(TRADE_ACTION_DEAL)` | `POST /api/trade/order/send`（或 dealer 通道） |
| **平仓** | `mt5.order_send(position=..., 反向)` | `POST /api/trade/position/close` |
| **改 SL/TP** | `mt5.order_send(TRADE_ACTION_SLTP)` | `POST /api/trade/order/modify` |
| 挂单 | `TRADE_ACTION_PENDING` | `POST /api/trade/order/send`（type=pending） |
| 持仓查询 | `mt5.positions_get()` | `POST /api/trade/position/get` |

> 上表端点路径需以**你券商的 Web API 文档**为准（不同版本略有差异）。
> 认证哈希（两次 `MD5` 挑战应答）实现前必须先用你的真实账号跑通握手，再写其余接口。

### 9.3 建议的 Java 工程结构（放于本目录）

```
FE Agent/
├─ MT4-AI核心方法技术文档.md          # 本文档
├─ pom.xml                            # Java 21 + Jackson + java.net.http（无 Spring 依赖）
└─ src/main/java/com/benmao/mt5/
    ├─ Mt5Config.java                 # Web API 地址 / 登录 / 密码 / 默认品种
    ├─ Mt5WebApiClient.java           # HTTP 传输 + 认证握手 + 会话保持
    ├─ Mt5TradingService.java         # 开仓/平仓/改SLTP/挂单/撤单
    ├─ Mt5MarketService.java          # tick 报价 + K 线(chart/get)
    ├─ Mt5AccountService.java         # 资金账户 + 持仓
    └─ model/                         # OrderRequest / OrderResult / KlineBar / AccountInfo / Position ...
```

方法命名将**对齐本文档**，便于逐条比对：

```java
OrderResult buyMarket(String symbol, double volume, double sl, double tp);
OrderResult sellMarket(String symbol, double volume, double sl, double tp);
OrderResult closePosition(String symbol, int ticket);
List<OrderResult> closeAllPositions(String symbol);
OrderResult modifySlTp(String symbol, int ticket, double sl, double tp);  // 修正 §5.2 缺陷
List<KlineBar> getKlines(String symbol, String timeframe, int count);
AccountInfo getAccountInfo();          // balance / equity / freeMargin ...
List<Position> getPositions(String symbol);
Quote getQuote(String symbol);
```

---

## 10. 需要你提供的信息（用于写 Java + 测试）

**先确认（决定方案是否可行，必须回答）**

1. 你的**券商是否开放 MT5 Web API**？即是否给你一个 **Web API 服务器地址（host:port）**？
2. 你有的是**普通交易账号**，还是 **manager / 机构账号**？（Web API 通常只认 manager 账号）

**若上述为「是」，需要提供：**

| # | 项目 | 示例 | 用途 |
| --- | --- | --- | --- |
| 1 | Web API 服务器地址 | `https://xxx.xxx.xxx:443` | 连接目标 |
| 2 | 登录账号 | `500476959` | `auth/start` 的 login |
| 3 | 密码 | `******` | 认证哈希计算 |
| 4 | 默认品种 | `XAUUSD` | K 线/下单默认品种 |
| 5 | 是否模拟盘 | 模拟/实盘 | 决定测试风险 |

> 若**没有** Web API（很可能），请改选 §9.1 方案 A（Java → 本地 Python 服务）：
> 那样只需提供 **MT5 账号 / 密码 / 服务器名**（与 Python 项目 `.env` 里 `MT5_LOGIN/MT5_PASSWORD/MT5_SERVER` 一致）即可。

---

## 11. 测试计划（步骤 3，拿到凭据后执行）

按「**只读 → 可逆 → 有风险**」的顺序，逐层验证，先模拟盘再实盘：

| 阶段 | 用例 | 预期 |
| --- | --- | --- |
| T0 | 认证握手 | `auth/start` + `auth/answer` 拿到会话，能调通 `user/account/get` |
| T1 | 账户资金（只读） | `getAccountInfo()` 返回 balance / equity / freeMargin，与 MT5 终端数字一致 |
| T2 | K 线（只读） | `getKlines("XAUUSD","M5",5)` 返回 5 根，OHLCV 与 MT5 图表一致 |
| T3 | 实时报价（只读） | `getQuote("XAUUSD")` 的 bid/ask 与终端跳动一致 |
| T4 | 开仓（最小手 0.01） | `buyMarket` 成功，`getPositions` 能看到该 ticket |
| T5 | 改 SL/TP | `modifySlTp` 后，持仓的 sl/tp 更新（且原 TP 不被误清——验证 §5.2 修复） |
| T6 | 平仓 | `closePosition(ticket)` 成功，`getPositions` 不再含该 ticket，资金回到账户 |
| T7 | 一键平仓/熔断 | `closeAllPositions` 清空所有持仓（模拟盘验证） |

测试原则：**每一步都先打印原始响应**，确认无误再进入下一步；实盘测试手数固定 0.01。

---

## 附录 A：核心常量

```text
TRADE_RETCODE_DONE = 10009          # 成功返回码
ORDER_TYPE_BUY = 0 / SELL = 1
TRADE_ACTION_DEAL = 市价成交
TRADE_ACTION_PENDING = 挂单
TRADE_ACTION_SLTP = 改止损止盈
TRADE_ACTION_REMOVE = 撤单
ORDER_TIME_GTC = 一直有效
Filling: FOK / IOC / RETURN（按品种自动探测）
```

## 附录 B：默认参数

```text
默认品种 MT5_SYMBOL = GOLD      # 注意：FxPro-MT5 Demo 的黄金品种名是 GOLD，不是 XAUUSD（其他券商可能叫 XAUUSD/GOLDm 等，以实际为准）
默认手数 MT5_VOLUME = 0.01
市价单滑点 deviation = 20
挂单滑点 deviation = 10
行情轮询间隔 = 1.0s
下单基础间隔 = 5s（失败指数退避至 300s）
```