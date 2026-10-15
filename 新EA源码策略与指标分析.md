# 新EA 源码策略 / 指标 / 参数 分析

> 分析对象：`D:\BaiduNetdiskDownload\新EA` 下所有 `.mq4` / `.mq5` 源码（去重后共 **9 个唯一源码**）。
> 原始文件为 GBK 编码，分析时已转码为 UTF-8 逐行阅读；本报告只记录源码中**实际出现**的策略、指标与参数默认值，含少量由 `.set` 预设文件交叉验证的结果。
> 说明：带 `(1)` 后缀的副本与原件字节一致，不重复列出。

---

## 0. 源码清单与一句话定位

| # | 源码文件 | 平台 | 策略类型 | 用到的指标 |
|---|---|---|---|---|
| 1 | `TSO-超级马丁同款 源码 T4\Experts\ttso.mq4` | MT4 | 挂单突破网格 + 马丁 | RSI（仅首单过滤，默认关） |
| 2 | `…\Experts\tTSO-RSI同款.mq4` | MT4 | 同上（同源变体） | RSI |
| 3 | `神猴shua单 T4 源码\神猴刷单EA.mq4` | MT4 | 市价刷单 + 整轮止盈 + 马丁 | **无指标**（仅用 M15 阴阳线） |
| 4 | `…\神猴刷单EA_V2.mq4` | MT4 | 同上 + 反向对冲 + 时间控制 | **无指标** |
| 5 | `谷歌黄金…源码 T5\谷歌黄金，修改了动态止盈损，动态仓位，.mq5` | MT5 | 单仓趋势跟随 | ATR / RSI / ADX / EMA(52) |
| 6 | `金麒麟，源码 T4\金麒麟去所有限制.mq4` | MT4 | 纯报价网格 / 对冲 | **无指标** |
| 7 | `飓风EA，带源码 T4\飓风刷单ecn版.mq4` | MT4 | 逆势回调刷单 + 马丁 | Bands/RSI/Stochastic/CCI（默认全关） |
| 8 | `…\飓风刷单ecn版 2.0 PLUS.mq4` | MT4 | 同上 + 百分比风控 | 同上 |
| 9 | `…\飓风刷单ecn版-A-美分.mq4` | MT4 | 同上（美分账户标定） | 同上 |

**同源聚类结论**
- **TSO 家族**：① `ttso.mq4` 与 ② `tTSO-RSI同款.mq4` 同源（共用 `cm_EA_TSO` 注释、均价移动止损逻辑；后者函数名被 MD5 混淆、版权清空）。
- **刷单家族**：③④ 神猴 与 ⑦⑧⑨ 飓风 高度疑似**同一"1mt5.com 刷单内核"的不同分支**（`定单识别码=10533`、`CountOrders`/虚拟下单/整轮均价止盈算法一致）。
- **金麒麟**：⑥ 与 `金麒麟ex5`（.ex5 无源码）为同一 EA 的新旧两版（`Magic=9527`）。
- 其余（谷歌、金麒麟）为独立产品。

---

## 1. TSO 超级马丁（`ttso.mq4` / `tTSO-RSI同款.mq4`，MT4）

### 策略
- **挂单突破网格**：非市价开仓，无持仓时按 `First_step` 挂 BUYSTOP/SELLSTOP；有持仓时按 `Minimum_price_distance` 续挂，随价格按 `Move_step` 移动挂单。
- `Open_order_on_trend=FALSE` → 默认**逆势网格**（不在趋势方向新挂）。
- **马丁加仓**：手数 `= Order_lotsize × Multiply_lotsize_by^层数 + 层数 × Increase_lotsize_by`，无最大层数限制、**无虚拟下单**。
- **出场**：默认不设单笔 SL/TP（`Stoploss=0 / Takeprofit=0`，仅当亏损≥经纪商 STOPLEVEL 时才设）；靠**均价移动止损** + 双向/单向整体盈利平仓 + 回撤平仓。

### 指标
- 仅 `iRSI`：`RSI_Period=5`，超卖 `Oversold_zone=15` / 超买 `Overbought_zone=85`。
- 用途仅为**首单过滤**，且默认关闭（`Opening_1_order_on_indicators=FALSE`）。网格加仓不依赖任何指标。

### 关键参数（默认值）
| 参数 | 默认 | 参数 | 默认 |
|---|---|---|---|
| Delete_Orders / DeleteHour | true / 20 | Order_lotsize | 0.1 |
| Allow_BUY / Allow_SELL | TRUE / TRUE | Multiply_lotsize_by | 1.5 |
| EA_makes_first_order | TRUE | Increase_lotsize_by | 0 |
| Open_order_on_trend | FALSE | Maximum_allowed_loss | 100000 |
| First_step | 10 | Close_loss_by_drawdown | 10 |
| Minimum_price_distance | 30 | Profit_for_closing_2_directions | 10 |
| Move_step | 5 | Profit_for_closing_1_direction | 50 |
| Distance_between_orders | 30 | Auto_calculated_profit | 50 |
| Loss_for_closing | 100000 | Trailing_type | 1（K线高低点） |
| Minimum_trailing_profit | 10 | Takeprofit / Stoploss | 0 / 0 |
| RSI_Period | 5 | Oversold/Overbought_zone | 15 / 85 |
| StartHour / EndHour | 0 / 24 | Magic | 777 |

### 与 `tTSO-RSI同款.mq4` 的差异
- 新增 `MaxOrders=9`，配 `ManageOrderLimit()`：超过即平掉最早订单。
- 默认值整体放大、平仓门槛近乎关闭：`First_step=100`、`Distance_between_orders=200`、`Move_step=20`、`Order_lotsize=0.01`、`Profit_for_closing_1/2=9999`、`Close_loss_by_drawdown=99999`、`Trailing_type=3`、`Trailing_step=10`、`Minimum_trailing_profit=45`、`Takeprofit=50`、`RSI_Period=3`、`Oversold/Overbought_zone=10/90`、`Magic=20150704`。
- 版权头清空、函数名改为 MD5 风格混淆串。

---

## 2. 神猴刷单 EA（`神猴刷单EA.mq4` / `神猴刷单EA_V2.mq4`，MT4）

### 策略
- **市价刷单**：每根新 M1 K 线检查空仓后，按 `多空方向` 直接市价开首单（无任何指标过滤）。
- **整轮均价止盈**：把同向所有单的 TP 统一设为「加权均价 + 总体盈利点数」，触价整向平仓；**无单笔 SL/TP、无移动止损**。
- **逆势马丁加仓**：加仓触发条件为「上一根 M15 为阳线才加多 / 阴线才加空」(`${iClose(M15,1) >= iOpen(M15,1)}`）+ 价格间隔；手数 `= min(单笔最大下单量, 起始下单量 × 翻倍^层数)`。
- **加仓间距随层数变化**；支持**虚拟下单**（前 N 单只记录不下单，超过后补下）。
- 面板含手动全部/多/空平仓按钮。

### 指标
- **无任何标准技术指标**。唯一的"信号"来自 M15 实体阴阳（`iClose`/`iOpen`）与价格间距。

### 关键参数（默认值）
| 参数 | 神猴 V1 (`神猴刷单EA.mq4`) | 神猴 V2 (`..._V2.mq4`) |
|---|---|---|
| 帐号限制 | 0（不限） | 0（不限） |
| 时间限制 | 2999.12.9（实际不限） | 2098.12.15 |
| 多空方向 | 2（多空都做） | 2 |
| 显示图表信息 / 显示止盈价格 | false / true | false / true |
| 启用虚拟下单 / 虚拟下单单数 | false / 10 | false / 0 |
| 显示浮亏单数 | 7 | 7 |
| 单向最大单数 | 15 | 15 |
| 单笔最大下单量（资金口径） | 5 | 资金2W单笔最大下单量=1 |
| 起始下单量 | 0.005 | 0.01 |
| 翻倍 | 1.6 | 1.6 |
| 加仓间距 | <7单20点，7–10单30，11–12单50，13单75 | 间隔单数=7；以下20点，以上 **5** 点 |
| 总体盈利点数 | 10 | 10 |
| 总亏损/总盈利平仓 | 0 / 0 | 0 / 0 |
| 滑点 / 定单识别码 | 3 / 10533 | 3 / 10533（反向单 Magic+1=10534） |

### V2 新增
- **反向对冲单**：`启用反向对冲单=false`、`反向下单量倍数=2`、`反向止损点数=10`、`反向加逆势整体盈利点数=1`。
- **时间控制**：`启用时间控制=false`、`电脑平台时间差=5`、`开始小时=21`、`结束小时=14`（只做亚洲盘）。
- 手数改为按 `AccountBalance()/20000 × 资金2W单笔最大下单量` 自动计算；新增 8 个 UI 控制按钮。

---

## 3. 谷歌黄金（`谷歌黄金，修改了动态止盈损，动态仓位，.mq5`，MT5）

### 策略
- **单仓趋势跟随**：同一时刻只持 1 单（`Magic=777888`），仅在新 Bar 且无持仓时下单。
- 入场条件（**三者同向**）：SuperTrend 方向 + QQE 动量 + EMA(52) 趋势基准，再用 **ADX** 过滤趋势强度，叠加交易时段过滤。
- 出场：`SL = 2×ATR`、`TP = 4×ATR`；移动止损 `1.5×ATR`，仅在保本（新 SL 超过开仓价）后启动，每 tick 检查。
- 动态手数：按账户余额 × 风险% ÷ 止损点值，并按品种最小/最大/步长规范化。
- **无加仓、无网格**。

### 指标（源码实际调用）
| 指标 | 函数 | 参数默认 |
|---|---|---|
| ATR | `iATR(_Symbol,_Period,InpATRPeriod)` | `InpATRPeriod=14` |
| RSI | `iRSI(...,PRICE_CLOSE)` | `InpRSI_Period=14` |
| ADX | `iADX(_Symbol,_Period,InpADXPeriod)` | `InpADXPeriod=14`，阈值 `InpADXThreshold=20` |
| EMA | `iMA(...,52,0,MODE_EMA,PRICE_CLOSE)` | 周期 52 |
| SuperTrend | —— | **未实现**：源码第 101–103 行为注释占位，实际用 `close vs EMA52` 近似方向 |
| QQE | —— | **未实现**：仅用 RSI 相对 50 的阈值近似 |

> 注：该文件更接近一份**未完成的策略骨架/模板**，SuperTrend、QQE 均为示意而非真实计算。

### 关键参数（默认值）
`InpFixedLots=0.1`、`InpUseMoneyMgmt=true`、`InpRiskPercent=2.0`、`InpSTPeriod=10`、`InpSTMultiplier=3.0`、`InpRSI_Period=14`、`InpUseADX=true`、`InpADXPeriod=14`、`InpADXThreshold=20.0`、`InpUseTimeFilter=true`、`InpStartHour=8`、`InpEndHour=20`、`InpATRPeriod=14`、`InpStopATR=2.0`、`InpTakeATR=4.0`、`InpUseTrailing=true`、`Magic=777888`。

> 交叉验证：同目录 `.set`（`0.01.set`）把 `InpFixedLots` 改为 0.01，其余与源码默认值逐项一致。

---

## 4. 金麒麟（`金麒麟去所有限制.mq4`，MT4）

### 策略
- **纯报价网格 / 对冲**，不使用任何常规指标。
- 按价格偏移量挂单：买入挂 BUY STOP 于 `Ask+FirstStep`，卖出挂 SELL STOP 于 `Bid-FirstStep`；方向由触发决定。
- 另受价格分界参数（`On_top_of_this_price_not_Buy_*` / `On_under_..._Sell_*`）与 `OpenMode`（1=按时区 A、2=按秒间距 B、3=不延迟 C）、`sleep` 秒间隔、时间窗控制。
- **出场以金额平仓为主**：单边止盈 `StopProfit`（`Profit=true` 时按单数累加），单边止损 `StopLoss`；整体平仓后 `Over=true` 停止、`NextTime` 秒后开新局。
- **对冲/顺势**：`CloseBuySell`（逆势保护）、`HomeopathyCloseAll`（顺势保护）、`Homeopathy`（完全对锁时挂顺势）。

### 指标
- **无任何指标**（全文件无 `iMA/iRSI/iMACD/iCustom` 等调用）。

### 加仓 / 马丁
- 手数 `= 层数 × PlusLot + lot × K_Lot^层数`，`K_Lot=1.3`，上限 `Maxlot=10`。
- 间距：`FirstStep=30` / 最小 `MinDistance=60` / 第二最小 `TwoMinDistance=60` / 补单 `Step=100` / 第二补单 `TwoStep=100`；浮亏超 `Money` 才启用第二套参数；最大层数 `Totals=50`。

### 关键参数（默认值）
`lot=0.01`、`Maxlot=10`、`K_Lot=1.3`、`PlusLot=0`、`Totals=50`、`FirstStep=30`、`MinDistance=60`、`TwoMinDistance=60`、`Step=100`、`TwoStep=100`、`Money=0`、`StopProfit=2.0`、`StopLoss=0`、`MaxLossCloseAll=50`、`MaxLoss=100000`、`CloseAll=0.5`、`Profit=true`、`OpenMode=3`、`TimeZone=1`、`sleep=30`、`MaxSpread=60`、`Leverage=100`、`CloseBuySell=true`、`HomeopathyCloseAll=true`、`Homeopathy=false`、`Over=false`、`NextTime=0`、`DigitsLot=2`、`EA_StartTime/StopTime=00:00/24:00`、`Magic=958999848`（源码默认）。

### "去所有限制"实际改了什么
- 联网授权函数 `lizong_18()` 被改为**恒定返回 true**（原 `InternetOpenW(...)` 已注释）。
- 承载平台/账号/期限校验的 `UseCheck()` **已无任何调用点**，故 `Time_Control`、`Use_Expiration_Time=D'2026.01.28'` 等原限制全部失效。
- 硬编码账号 switch 为残留死代码（结果未被再读取）。

> 交叉验证：同目录 `金麒麟优化.set` 的 `FirstStep30/MinDistance60/Step100/TwoStep100/K_Lot1.3/Totals50/MaxSpread60/Leverage100` 与源码默认值逐项一致（仅 `Magic` 改为 9527）。

---

## 5. 飓风刷单（`飓风刷单ecn版.mq4` / `2.0 PLUS` / `-A-美分`，MT4）

### 策略
- **逆势回调刷单**：跟踪本轮 Low/High 极值，价格先逆势走、再向盈利方向反弹 ≥ `逆势回调点数` 才入场。
- **整轮均价止盈**：同向所有单 TP 统一设为「加权均价 +（逆势盈利点数 − 递减×(n−1)，不低于最小值）」，触价整向平仓；单笔时用 `顺势盈利点数`。无单笔 SL/TP。
- **马丁加仓**：手数 `= 起始下单量 × 翻倍^同向单数`，上限 `= 余额/50000 × 资金5W单笔最大下单量`；逆势加码间隔可递减，可选顺势加码。
- **虚拟下单**：前 N 单（默认 10）或手数小于平台最小手时只画图/存数组不下单，超过后补下。
- 可选「整体移动止损」「按金额总亏损/总盈利平仓」（默认均关）。

### 指标（源码实际调用，默认全部关闭）
| 指标 | 参数默认 | 用法 |
|---|---|---|
| iBands | `启用iBands控制=false`、平均周期 20、偏差 2、平移 0、应用 PRICE_CLOSE | 下轨做多 / 上轨做空 |
| iRSI | `启用iRSI控制=false`、周期 14、应用 PRICE_CLOSE、上线 70 / 下线 30 | 超卖做多 / 超买做空 |
| iStochastic | `启用iStochastic控制=false`、K=5、D=3、慢速=3、MA=0、价格=0、上线 80 / 下线 20 | 同上 |
| iCCI | `启用iCCI控制=false`、周期 14、上线 100 / 下线 −100 | 同上 |

> 另含一个**从未被调用**的"均线过滤"死函数（`2.0 PLUS` 已将其删除）。

### 关键参数（默认值）
`多空方向=2`、`起始下单量=0.01`、`翻倍=1.5`、`资金5W单笔最大下单量=3`、`虚拟下单单数=10`、`显示浮亏单数=7`、`单向最大单数=25`、`顺势最大单数=50`、`逆势回调点数=1`、`逆势加码间隔=5`、`逆势加码间隔递减=0.1`、`逆势加码间隔最小=1`、`顺势加码间隔=9`、`逆势盈利点数=2.5`、`逆势盈利点数递减=0.1`、`逆势盈利点数最小=0.5`、`顺势盈利点数=10`、`整体移动止损点数=0`、`总亏损平仓=0`、`总盈利平仓=0`、`滑点=3`、`定单识别码=123123`、`区间K线数=8`、`时间限制=2097.12.9`、`帐号限制=0`。

### 三个变体差异
| 变体 | 差异 |
|---|---|
| **ecn版** | 基准版本（版权头仍写"飓风刷单2.0"） |
| **2.0 PLUS** | 与 ecn 逐行几乎相同；新增**百分比风控**：`百分比止损开关=false / 总亏百分比平仓=80`、`百分比止盈开关=false / 总盈百分比平仓=3`；删除均线过滤函数 |
| **-A-美分** | 同为同一内核；多数参数改为**非 extern（UI 不可调）**，默认值按美分账户重标定：`逆势回调点数=10`、`逆势加码间隔=200`（递减 0.01 / 最小 10）、`顺势加码间隔=360`、`逆势盈利点数=0.25`（递减 0.01 / 最小 0.05）、`顺势盈利点数=1`、`滑点=30` |

> 交叉验证：同目录 `飓风布林.set`、`飓风黄金固定止盈0.02手.set` 的参数名与源码完全对应（如 `单向最大单数=25`、`顺势最大单数=50`、`虚拟下单单数=10`、`定单识别码=10533/123123`、iBands/iRSI/iStochastic/iCCI 全套）。

---

## 6. 指标使用汇总

| 源码 | 实际使用指标 | 是否参与开仓/加仓决策 |
|---|---|---|
| ttso / tTSO-RSI | RSI(5) | 仅首单过滤，默认关 |
| 神猴 V1/V2 | 无（仅 M15 阴阳 + 价格间距） | 加仓触发用 M15 实体方向 |
| 谷歌黄金 mq5 | ATR/RSI/ADX/EMA52（SuperTrend、QQE 未实现） | 是，三者同向 + ADX 过滤 |
| 金麒麟 | 无 | 纯价格偏移挂单 |
| 飓风三变体 | iBands/iRSI/iStochastic/iCCI | 可选过滤，默认全关 |

## 7. 说明
- 本报告基于源码逐行阅读，参数取值为**源码 `extern/input` 默认值**；实际使用值以各自 `.set` 预设为准（已在相关小节标注交叉验证结果）。
- 涉及的 `.ex4/.ex5` 编译体不在本报告范围（正文加密，无法反编译）。
