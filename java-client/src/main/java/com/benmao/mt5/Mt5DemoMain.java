package com.benmao.mt5;

import com.benmao.mt5.model.AccountInfo;
import com.benmao.mt5.model.KlineBar;
import com.benmao.mt5.model.OrderResult;
import com.benmao.mt5.model.Position;
import com.benmao.mt5.model.Quote;

import java.util.List;
import java.util.Map;

/**
 * 可直接运行的演示 / 自测程序。
 *
 * <pre>
 * 只读自测（默认）：  mvn -q exec:java
 * 含下单自测：        mvn -q exec:java -Dexec.args="trade"
 * </pre>
 *
 * 前置条件：
 *   1) 本地 Python 服务已启动（mt5_service/start_service.bat）
 *   2) MT5 终端已登录，且“算法交易 / Algo Trading”按钮已打开
 */
public class Mt5DemoMain {

    public static void main(String[] args) {
        boolean doTrade = args.length > 0 && "trade".equalsIgnoreCase(args[0]);
        String symbol = args.length > 1 ? args[1] : Mt5Config.DEFAULT_SYMBOL;

        Mt5 mt5 = new Mt5();

        System.out.println("========= 1) 连通性 /health =========");
        Map<String, Object> health = mt5.account.health();
        health.forEach((k, v) -> System.out.println("  " + k + " = " + v));
        if (!Boolean.TRUE.equals(health.get("ok"))) {
            System.out.println("服务未就绪，退出。");
            return;
        }

        System.out.println("\n========= 2) 账户资金 /account（还剩多少 U）=========");
        AccountInfo acc = mt5.account.getAccountInfo();
        System.out.printf("  账号=%d  服务器=%s  币种=%s%n", acc.login(), acc.server(), acc.currency());
        System.out.printf("  结余 balance     = %.2f%n", acc.balance());
        System.out.printf("  净值 equity      = %.2f%n", acc.equity());
        System.out.printf("  可用保证金 free  = %.2f  <= 还能用来开仓的钱%n", acc.freeMargin());
        System.out.printf("  浮动盈亏 profit  = %.2f   杠杆=%d%n", acc.profit(), acc.leverage());

        System.out.println("\n========= 3) 实时报价 /quote =========");
        Quote q = mt5.market.getQuote(symbol);
        System.out.printf("  %s  bid=%.2f  ask=%.2f  spread=%.0f  digits=%d%n",
                q.symbol(), q.bid(), q.ask(), q.spread(), q.digits());

        System.out.println("\n========= 4) K 线 /klines（每根 OHLCV，M5 最近 5 根）=========");
        List<KlineBar> bars = mt5.market.getKlines(symbol, "M5", 5);
        for (KlineBar b : bars) {
            System.out.printf("  %s  O=%.2f H=%.2f L=%.2f C=%.2f V=%d%n",
                    b.instant(), b.open(), b.high(), b.low(), b.close(), b.volume());
        }

        System.out.println("\n========= 5) 当前持仓 /positions =========");
        List<Position> positions = mt5.account.getPositions(symbol);
        if (positions.isEmpty()) {
            System.out.println("  （无持仓）");
        } else {
            for (Position p : positions) {
                System.out.printf("  #%d %s %s vol=%.2f open=%.2f now=%.2f SL=%.2f TP=%.2f profit=%.2f%n",
                        p.ticket(), p.symbol(), p.type(), p.volume(), p.openPrice(),
                        p.currentPrice(), p.sl(), p.tp(), p.profit());
            }
        }

        if (!doTrade) {
            System.out.println("\n（只读自测完成。加参数 trade 可测开仓/改TP-SL/平仓：mvn -q exec:java -Dexec.args=\"trade\"）");
            return;
        }

        // ---------------- 下单自测（模拟盘，最小手 0.01）----------------
        System.out.println("\n========= 6) 开仓 buy 0.01（带 SL/TP）=========");
        double sl = round2(q.bid() - 100);
        double tp = round2(q.bid() + 100);
        OrderResult buy = mt5.trading.buyMarket(symbol, 0.01, sl, tp);
        System.out.println("  " + buy);
        if (!buy.success()) {
            System.out.println("  开仓失败：" + explain(buy.retcode()));
            return;
        }

        System.out.println("\n========= 7) 查回持仓、取 ticket =========");
        Position opened = mt5.account.findPosition(buy.order() != null ? buy.order() : -1);
        if (opened == null) {
            List<Position> all = mt5.account.getPositions(symbol);
            opened = all.isEmpty() ? null : all.get(0);
        }
        if (opened == null) {
            System.out.println("  未找到刚开的持仓，终止。");
            return;
        }
        long ticket = opened.ticket();
        System.out.printf("  ticket=%d  SL=%.2f TP=%.2f%n", ticket, opened.sl(), opened.tp());

        System.out.println("\n========= 8) 只改 SL，验证 TP 不被清空 =========");
        double newSl = round2(q.bid() - 50);
        OrderResult mod = mt5.trading.modifySl(symbol, ticket, newSl);
        System.out.println("  " + mod);
        Position afterMod = mt5.account.findPosition(ticket);
        if (afterMod != null) {
            System.out.printf("  改后 SL=%.2f TP=%.2f（TP 应仍为 %.2f）%n", afterMod.sl(), afterMod.tp(), tp);
        }

        System.out.println("\n========= 9) 平仓 close =========");
        OrderResult close = mt5.trading.closePosition(symbol, ticket);
        System.out.println("  " + close);

        System.out.println("\n========= 10) 平仓后持仓与资金 =========");
        System.out.println("  剩余持仓数 = " + mt5.account.getPositions(symbol).size());
        AccountInfo accAfter = mt5.account.getAccountInfo();
        System.out.printf("  结余=%.2f  净值=%.2f  可用=%.2f%n",
                accAfter.balance(), accAfter.equity(), accAfter.freeMargin());

        System.out.println("\n下单自测完成。");
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 把 MT5 返回码翻译成人话，便于定位失败原因。 */
    private static String explain(Integer retcode) {
        if (retcode == null) {
            return "无返回码（请求未送达，检查本地服务是否启动）";
        }
        return switch (retcode) {
            case 10009 -> "成功";
            case 10027 -> "MT5 终端的“算法交易 / Algo Trading”按钮未打开";
            case 10018 -> "市场已休市（周末/节假日），请开盘后再试";
            case 10019 -> "资金不足";
            case 10014 -> "手数无效或超出限制";
            case 10016 -> "止损/止盈价格无效（离市价太近或方向错误）";
            case 10015 -> "价格无效";
            case 10013 -> "请求参数无效";
            default -> "retcode=" + retcode + "，详见 MT5 交易返回码表";
        };
    }
}