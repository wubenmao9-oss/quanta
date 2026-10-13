"""MT5 本地服务 — 用 MetaTrader5 库封装核心交易能力，供 Java 通过 HTTP 调用。

只做 6 类事（对齐《MT4-AI核心方法技术文档.md》）：
  1) 开仓   POST /order/buy        POST /order/sell
  2) 平仓   POST /order/close       POST /order/close_all
  3) TP/SL  POST /order/modify      （修复了原项目 modify_sl 会把 TP 清零的缺陷）
  4) 行情   GET  /quote             GET  /klines（每根 K 线）
  5) 资金   GET  /account           GET  /positions
  6) 会话   POST /connect           GET  /symbols（品种清单，支撑名称映射）

运行前提：本机 MT5 终端已安装并登录（本项目为 E:\\mt5\\terminal64.exe）。
启动：  uvicorn server:app --host 127.0.0.1 --port 8765
"""

from __future__ import annotations

import os
import time
from datetime import datetime, timezone
from typing import Optional

import MetaTrader5 as mt5
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

load_dotenv()

# ======================================================================
# 配置
# ======================================================================
MT5_LOGIN = int(os.getenv("MT5_LOGIN", "0") or 0)
MT5_PASSWORD = os.getenv("MT5_PASSWORD", "")
MT5_SERVER = os.getenv("MT5_SERVER", "")
MT5_PATH = os.getenv("MT5_PATH", "")          # 可选：terminal64.exe 完整路径
DEFAULT_SYMBOL = os.getenv("MT5_SYMBOL", "XAUUSD")

TIMEFRAMES = {
    "M1": mt5.TIMEFRAME_M1, "M5": mt5.TIMEFRAME_M5, "M15": mt5.TIMEFRAME_M15,
    "M30": mt5.TIMEFRAME_M30, "H1": mt5.TIMEFRAME_H1, "H4": mt5.TIMEFRAME_H4,
    "D1": mt5.TIMEFRAME_D1, "W1": mt5.TIMEFRAME_W1, "MN1": mt5.TIMEFRAME_MN1,
}

# ======================================================================
# 连接与工具（逻辑取自项目 mt5_executor.py）
# ======================================================================
_connected = False
_filling_cache: dict[str, int] = {}
_last_order_time = 0.0
_consecutive_failures = 0
_MIN_ORDER_INTERVAL = 5.0     # 下单基础间隔（秒）
_MAX_ORDER_INTERVAL = 300.0   # 退避上限


def ensure_connected() -> None:
    """连接本机 MT5 终端（幂等）。"""
    global _connected
    if _connected and mt5.terminal_info() is not None:
        return
    kwargs = {}
    if MT5_LOGIN and MT5_PASSWORD and MT5_SERVER:
        kwargs = dict(login=MT5_LOGIN, password=MT5_PASSWORD, server=MT5_SERVER)
    ok = mt5.initialize(path=MT5_PATH, **kwargs) if MT5_PATH else mt5.initialize(**kwargs)
    if not ok:
        raise RuntimeError(f"MT5 连接失败: {mt5.last_error()}")
    _connected = True


def ensure_symbol(symbol: str) -> None:
    if not mt5.symbol_select(symbol, True):
        raise RuntimeError(f"选中品种失败 {symbol}: {mt5.last_error()}")


def _session_payload(ok: bool, error: Optional[str] = None) -> dict:
    """会话状态统一返回体（供 /health 与 /connect 复用）。"""
    ti = mt5.terminal_info()
    ai = mt5.account_info()
    d = {
        "ok": ok,
        "terminal_connected": ti is not None,
        "login": ai.login if ai else None,
        "server": ai.server if ai else None,
    }
    if error:
        d["error"] = error
    return d


def connect_with_credentials(login: int, password: str, server: str, path: Optional[str] = None) -> dict:
    """用指定凭据登录 MT5 终端（幂等：已是同一账号则直接返回，不重连）。

    供 Java 侧用 sys_config 里存的 MT5 凭据真实登录（M1 的「测试连接」只探活 /health）。
    """
    global _connected
    ai = mt5.account_info() if _connected else None
    if ai is not None and int(ai.login) == int(login) and (not server or ai.server == server):
        return _session_payload(True)

    mt5.shutdown()
    _connected = False
    _filling_cache.clear()          # 换账号后填充模式可能不同，必须重新探测
    kwargs = dict(login=int(login), password=password, server=server)
    ok = mt5.initialize(path=path, **kwargs) if path else mt5.initialize(**kwargs)
    if not ok:
        return _session_payload(False, f"MT5 登录失败: {mt5.last_error()}")
    _connected = True
    return _session_payload(True)


def get_filling(symbol: str) -> int:
    """按品种自动探测填充模式 FOK/IOC/RETURN（不同券商支持不同，写错会下单失败）。"""
    if symbol in _filling_cache:
        return _filling_cache[symbol]
    info = mt5.symbol_info(symbol)
    if info is None:
        return mt5.ORDER_FILLING_IOC
    fm = info.filling_mode          # 位掩码: bit1=IOC, bit0=FOK, 否则 RETURN
    filling = mt5.ORDER_FILLING_IOC if fm & 2 else (
        mt5.ORDER_FILLING_FOK if fm & 1 else mt5.ORDER_FILLING_RETURN)
    _filling_cache[symbol] = filling
    return filling


def check_rate_limit() -> Optional[str]:
    """下单限流 + 指数退避；被限流时返回错误文案。"""
    global _last_order_time
    now = time.time()
    interval = min(_MIN_ORDER_INTERVAL * (2 ** _consecutive_failures), _MAX_ORDER_INTERVAL)
    if now - _last_order_time < interval:
        return f"Rate limited, retry after {interval - (now - _last_order_time):.0f}s"
    _last_order_time = now
    return None


def format_result(result, request: Optional[dict] = None) -> dict:
    """归一化 mt5.order_send 结果。"""
    if result is None:
        return {"success": False, "error": str(mt5.last_error())}
    success = result.retcode == mt5.TRADE_RETCODE_DONE
    d = {
        "success": success,
        "retcode": result.retcode,
        "order": result.order,
        "deal": result.deal,
        "volume": result.volume,
        "price": request.get("price", result.price) if request else result.price,
        "comment": result.comment,
    }
    if not success:
        d["error"] = f"retcode={result.retcode} comment={result.comment}"
    return d


def balance_now() -> float:
    acc = mt5.account_info()
    return acc.balance if acc else 0.0


def ts_to_iso(ts: Optional[int]) -> Optional[str]:
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat() if ts else None


# ======================================================================
# 交易核心
# ======================================================================
def _market_order(symbol: str, is_buy: bool, volume: float, sl: float = 0.0, tp: float = 0.0,
                  comment: str = "java client") -> dict:
    global _consecutive_failures
    limit = check_rate_limit()
    if limit:
        return {"success": False, "error": limit}

    ensure_connected()
    ensure_symbol(symbol)
    tick = mt5.symbol_info_tick(symbol)
    if tick is None:
        return {"success": False, "error": f"品种 {symbol} 无报价"}

    price = tick.ask if is_buy else tick.bid
    request = {
        "action": mt5.TRADE_ACTION_DEAL,
        "symbol": symbol,
        "volume": volume,
        "type": mt5.ORDER_TYPE_BUY if is_buy else mt5.ORDER_TYPE_SELL,
        "price": price,
        "deviation": 20,
        "magic": 0,
        "comment": comment,
        "type_time": mt5.ORDER_TIME_GTC,
        "type_filling": get_filling(symbol),
    }
    if sl > 0:
        request["sl"] = sl
    if tp > 0:
        request["tp"] = tp

    result = mt5.order_send(request)
    formatted = format_result(result, request)
    _consecutive_failures = 0 if formatted["success"] else _consecutive_failures + 1
    return formatted


def _close_position(symbol: str, ticket: int) -> dict:
    """平单个仓位：开一张反向市价单并绑定 position=ticket。"""
    ensure_connected()
    pos = mt5.positions_get(ticket=ticket)
    if not pos:
        return {"success": False, "error": f"未找到持仓 ticket={ticket}"}
    p = pos[0]
    tick = mt5.symbol_info_tick(p.symbol)
    if tick is None:
        return {"success": False, "error": f"品种 {p.symbol} 无报价"}
    is_buy = p.type == mt5.ORDER_TYPE_BUY
    request = {
        "action": mt5.TRADE_ACTION_DEAL,
        "symbol": p.symbol,
        "volume": p.volume,
        "type": mt5.ORDER_TYPE_SELL if is_buy else mt5.ORDER_TYPE_BUY,
        "position": p.ticket,
        "price": tick.bid if is_buy else tick.ask,
        "deviation": 20,
        "magic": p.magic,
        "comment": "java close",
        "type_time": mt5.ORDER_TIME_GTC,
        "type_filling": get_filling(p.symbol),
    }
    formatted = format_result(mt5.order_send(request), request)
    formatted["closed_ticket"] = ticket
    formatted["profit"] = round(p.profit, 2)
    return formatted


def _close_all(symbol: str = "") -> list[dict]:
    ensure_connected()
    positions = mt5.positions_get(symbol=symbol) if symbol else mt5.positions_get()
    if not positions:
        return []
    return [_close_position(p.symbol, p.ticket) for p in positions]


def _modify_sl_tp(symbol: str, ticket: int, sl: Optional[float], tp: Optional[float]) -> dict:
    """修改持仓的 SL/TP。sl 或 tp 传 None 表示保留原值（避免像原项目那样把 TP 清零）。"""
    ensure_connected()
    pos = mt5.positions_get(ticket=ticket)
    if not pos:
        return {"success": False, "error": f"未找到持仓 ticket={ticket}"}
    p = pos[0]
    new_sl = p.sl if sl is None else sl
    new_tp = p.tp if tp is None else tp
    request = {
        "action": mt5.TRADE_ACTION_SLTP,
        "symbol": p.symbol,
        "position": ticket,
        "sl": new_sl,
        "tp": new_tp,
    }
    result = mt5.order_send(request)
    if result is None:
        return {"success": False, "error": str(mt5.last_error())}
    return {
        "success": result.retcode == mt5.TRADE_RETCODE_DONE,
        "retcode": result.retcode,
        "comment": result.comment,
        "sl": new_sl,
        "tp": new_tp,
    }


def _cancel_all_pending(symbol: str = "") -> list[dict]:
    ensure_connected()
    orders = mt5.orders_get(symbol=symbol) if symbol else mt5.orders_get()
    if not orders:
        return []
    out = []
    for o in orders:
        r = mt5.order_send({"action": mt5.TRADE_ACTION_REMOVE, "order": o.ticket})
        out.append({
            "success": r is not None and r.retcode == mt5.TRADE_RETCODE_DONE,
            "order": o.ticket, "symbol": o.symbol,
        })
    return out


# ======================================================================
# FastAPI
# ======================================================================
app = FastAPI(title="MT5 Local Service", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


class MarketOrderReq(BaseModel):
    symbol: str = DEFAULT_SYMBOL
    volume: float = 0.01
    sl: float = 0.0
    tp: float = 0.0


class CloseReq(BaseModel):
    symbol: str = ""
    ticket: int


class CloseAllReq(BaseModel):
    symbol: str = ""


class ModifyReq(BaseModel):
    symbol: str = DEFAULT_SYMBOL
    ticket: int
    sl: Optional[float] = None      # None = 保持原值
    tp: Optional[float] = None


class ConnectReq(BaseModel):
    login: Optional[int] = None
    password: Optional[str] = None
    server: Optional[str] = None
    path: Optional[str] = None      # 可选：terminal64.exe 完整路径


@app.get("/health")
def health():
    try:
        ensure_connected()
        ti = mt5.terminal_info()
        ai = mt5.account_info()
        return {
            "ok": True,
            "terminal_connected": ti is not None,
            "terminal_path": ti.path if ti else None,
            "login": ai.login if ai else None,
            "server": ai.server if ai else None,
            "currency": ai.currency if ai else None,
        }
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.post("/connect")
def connect(req: ConnectReq):
    """用指定凭据真实登录 MT5 终端（幂等）。缺省字段回落到 .env 配置。"""
    login = req.login or MT5_LOGIN
    password = req.password or MT5_PASSWORD
    server = req.server or MT5_SERVER
    if not (login and password and server):
        return {"ok": False, "error": "缺少凭据：login / password / server 需同时提供"}
    try:
        return connect_with_credentials(login, password, server, req.path or MT5_PATH or None)
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.get("/account")
def account():
    """账户资金（‘还剩多少 U’）。"""
    ensure_connected()
    acc = mt5.account_info()
    if acc is None:
        raise HTTPException(503, f"account_info 不可用: {mt5.last_error()}")
    return {
        "login": acc.login, "name": acc.name, "server": acc.server, "currency": acc.currency,
        "balance": acc.balance,        # 结余（本金）
        "equity": acc.equity,          # 净值 = balance ± 浮动盈亏
        "margin": acc.margin,          # 已用保证金
        "free_margin": acc.margin_free,  # 可用保证金（可用于开仓的钱）
        "profit": acc.profit,          # 浮动盈亏
        "leverage": acc.leverage,
    }


@app.get("/quote")
def quote(symbol: str = Query(DEFAULT_SYMBOL)):
    ensure_connected()
    ensure_symbol(symbol)
    tick = mt5.symbol_info_tick(symbol)
    info = mt5.symbol_info(symbol)
    if tick is None or info is None:
        raise HTTPException(503, f"品种 {symbol} 无数据")
    return {
        "symbol": symbol, "bid": tick.bid, "ask": tick.ask,
        "spread": info.spread, "digits": info.digits, "time": tick.time,
    }


@app.get("/symbols")
def symbols(query: str = Query(""), limit: int = Query(2000, ge=1, le=10000)):
    """券商可用品种清单（支撑 Java 侧名称映射层：XAUUSD → FxPro 实测名 GOLD）。"""
    ensure_connected()
    all_syms = mt5.symbols_get()
    names = [s.name for s in (all_syms or [])]
    if query:
        q = query.upper()
        names = [n for n in names if q in n.upper()]
    return {"count": len(names), "default": DEFAULT_SYMBOL, "symbols": names[:limit]}


def _rates_to_json(rates) -> list[dict]:
    if rates is None or len(rates) == 0:
        return []
    return [
        {"time": int(r[0]), "open": round(float(r[1]), 5), "high": round(float(r[2]), 5),
         "low": round(float(r[3]), 5), "close": round(float(r[4]), 5), "volume": int(r[5])}
        for r in rates
    ]


@app.get("/klines")
def klines(symbol: str = Query(DEFAULT_SYMBOL), timeframe: str = Query("M5"),
           count: int = Query(200, ge=1, le=5000)):
    """每根 K 线（OHLCV），取最近 ``count`` 根。"""
    tf = TIMEFRAMES.get(timeframe.upper())
    if tf is None:
        raise HTTPException(400, f"不支持的周期: {timeframe}，可选 {list(TIMEFRAMES)}")
    ensure_connected()
    ensure_symbol(symbol)
    return _rates_to_json(mt5.copy_rates_from_pos(symbol, tf, 0, count))


@app.get("/klines/range")
def klines_range(symbol: str = Query(DEFAULT_SYMBOL), timeframe: str = Query("M1"),
                 from_ts: int = Query(..., description="窗口起始（UTC epoch 秒，含）"),
                 to_ts: int = Query(..., description="窗口结束（UTC epoch 秒，含）")):
    """按时间区间取 K 线（OHLCV），用于回测历史回填。

    历史回填必须用「区间」而非「偏移」：MT5 终端的 ``copy_rates_from_pos`` 的 start 偏移只读
    已加载到图表的历史（本地仅数千根），而 ``copy_rates_range`` 会按需向服务器补下历史，
    因此可稳定取到更早的 M1 数据。
    """
    if to_ts <= from_ts:
        raise HTTPException(400, "to_ts 必须大于 from_ts")
    tf = TIMEFRAMES.get(timeframe.upper())
    if tf is None:
        raise HTTPException(400, f"不支持的周期: {timeframe}，可选 {list(TIMEFRAMES)}")
    ensure_connected()
    ensure_symbol(symbol)
    date_from = datetime.fromtimestamp(from_ts, timezone.utc).replace(tzinfo=None)
    date_to = datetime.fromtimestamp(to_ts, timezone.utc).replace(tzinfo=None)
    return _rates_to_json(mt5.copy_rates_range(symbol, tf, date_from, date_to))


@app.get("/positions")
def positions(symbol: str = Query("")):
    ensure_connected()
    ps = mt5.positions_get(symbol=symbol) if symbol else mt5.positions_get()
    if not ps:
        return []
    return [{
        "ticket": p.ticket, "symbol": p.symbol,
        "type": "buy" if p.type == mt5.ORDER_TYPE_BUY else "sell",
        "volume": p.volume, "open_price": p.price_open, "current_price": p.price_current,
        "sl": p.sl, "tp": p.tp, "profit": round(p.profit, 2), "swap": round(p.swap, 2),
        "magic": p.magic, "comment": p.comment, "open_time": ts_to_iso(p.time),
    } for p in ps]


@app.get("/history/deals")
def history_deals(from_ts: int = Query(...), to_ts: int = Query(...), symbol: str = Query("")):
    """指定 [from_ts, to_ts] 区间的成交历史（UTC epoch 秒）。

    symbol 为空 = 全部品种。对账用：entry=0 为开仓成交(IN)、entry=1 为平仓成交(OUT)，
    position_id 即持仓 ticket，可与本地 trade_order.ticket 对齐。
    """
    if to_ts <= from_ts:
        raise HTTPException(400, "to_ts 必须大于 from_ts")
    ensure_connected()
    date_from = datetime.fromtimestamp(from_ts, timezone.utc).replace(tzinfo=None)
    date_to = datetime.fromtimestamp(to_ts, timezone.utc).replace(tzinfo=None)
    deals = mt5.history_deals_get(date_from, date_to)
    if not deals:
        return []
    out = []
    for d in deals:
        if symbol and d.symbol != symbol:
            continue
        out.append({
            "ticket": d.ticket, "order": d.order, "position_id": d.position_id,
            "symbol": d.symbol,
            "type": "buy" if d.type == mt5.DEAL_TYPE_BUY else "sell",
            "entry": d.entry,  # 0=IN 1=OUT 2=INOUT 3=OUT_BY
            "volume": d.volume, "price": d.price,
            "profit": round(d.profit, 2), "swap": round(d.swap, 2), "commission": round(d.commission, 2),
            "time": ts_to_iso(d.time), "comment": d.comment,
        })
    return out


@app.post("/order/buy")
def order_buy(req: MarketOrderReq):
    return _market_order(req.symbol, True, req.volume, req.sl, req.tp, "java buy")


@app.post("/order/sell")
def order_sell(req: MarketOrderReq):
    return _market_order(req.symbol, False, req.volume, req.sl, req.tp, "java sell")


@app.post("/order/close")
def order_close(req: CloseReq):
    return _close_position(req.symbol, req.ticket)


@app.post("/order/close_all")
def order_close_all(req: CloseAllReq):
    return _close_all(req.symbol)


@app.post("/order/modify")
def order_modify(req: ModifyReq):
    if req.sl is None and req.tp is None:
        raise HTTPException(400, "sl 与 tp 至少提供一个")
    return _modify_sl_tp(req.symbol, req.ticket, req.sl, req.tp)


@app.post("/order/cancel_all")
def order_cancel_all(req: CloseAllReq):
    return _cancel_all_pending(req.symbol)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=int(os.getenv("PORT", "8765")))