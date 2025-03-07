package org.knowm.xchange.lgo;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order.OrderType;
import org.knowm.xchange.dto.marketdata.OrderBook;
import org.knowm.xchange.dto.marketdata.Trades;
import org.knowm.xchange.dto.meta.CurrencyMetaData;
import org.knowm.xchange.dto.meta.ExchangeMetaData;
import org.knowm.xchange.dto.meta.FeeTier;
import org.knowm.xchange.dto.meta.InstrumentMetaData;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.dto.trade.UserTrade;
import org.knowm.xchange.dto.trade.UserTrades;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.lgo.dto.WithCursor;
import org.knowm.xchange.lgo.dto.currency.LgoCurrencies;
import org.knowm.xchange.lgo.dto.currency.LgoCurrency;
import org.knowm.xchange.lgo.dto.key.LgoKey;
import org.knowm.xchange.lgo.dto.marketdata.LgoOrderbook;
import org.knowm.xchange.lgo.dto.order.LgoPlaceCancelOrder;
import org.knowm.xchange.lgo.dto.order.LgoPlaceLimitOrder;
import org.knowm.xchange.lgo.dto.order.LgoPlaceMarketOrder;
import org.knowm.xchange.lgo.dto.order.LgoPlaceOrder;
import org.knowm.xchange.lgo.dto.order.LgoUnencryptedOrder;
import org.knowm.xchange.lgo.dto.product.LgoProduct;
import org.knowm.xchange.lgo.dto.product.LgoProducts;
import org.knowm.xchange.lgo.dto.trade.LgoUserTrade;
import org.knowm.xchange.lgo.dto.trade.LgoUserTrades;

public final class LgoAdapters {

  private LgoAdapters() {}

  private static SimpleDateFormat ISO_DATE_FORMAT;

  static {
    ISO_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    ISO_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
  }

  public static ExchangeMetaData adaptMetadata(
      ExchangeMetaData metaData, LgoProducts products, LgoCurrencies currencies) {
    Map<Instrument, InstrumentMetaData> currencyPairs = metaData.getInstruments();
    Map<Currency, CurrencyMetaData> currency = metaData.getCurrencies();
    for (LgoCurrency lgoCurrency : currencies.getCurrencies()) {
      currency.put(
          Currency.getInstance(lgoCurrency.getCode()),
          new CurrencyMetaData(lgoCurrency.getDecimals(), null));
    }
    for (LgoProduct product : products.getProducts()) {
      BigDecimal minAmount = product.getBase().getLimits().getMin();
      BigDecimal maxAmount = product.getBase().getLimits().getMax();
      Integer baseScale = currency.get(Currency.getInstance(product.getBase().getId())).getScale();
      BigDecimal increment = product.getQuote().getIncrement().stripTrailingZeros();
      currencyPairs.put(
          toPair(product),
          new InstrumentMetaData.Builder()
                  .minimumAmount(minAmount)
                  .maximumAmount(maxAmount)
                  .volumeScale(baseScale)
                  .priceScale(increment.scale())
                  .amountStepSize(increment)
                  .feeTiers(new FeeTier[0])
                  .tradingFeeCurrency(Currency.USD)
                  .marketOrderEnabled(true)
                  .build());
    }
    return metaData;
  }

  private static CurrencyPair toPair(LgoProduct product) {
    return new CurrencyPair(
        Currency.getInstance(product.getBase().getId()),
        Currency.getInstance(product.getQuote().getId()));
  }

  public static Stream<LgoKey> adaptKeysIndex(String indexFile) {
    String[] all = indexFile.split("(true|false)");
    Spliterator<String> spliterator =
        Spliterators.spliterator(all, Spliterator.IMMUTABLE | Spliterator.NONNULL);
    return StreamSupport.stream(spliterator, true).map(LgoAdapters::parseSummary);
  }

  private static LgoKey parseSummary(String rawSummary) {
    String[] summary = rawSummary.split(" ");
    return new LgoKey(summary[0], Instant.parse(summary[1]), Instant.parse(summary[2]));
  }

  public static LgoPlaceOrder adaptLimitOrder(LimitOrder limitOrder) {
    String product = adaptCurrencyPair(limitOrder.getCurrencyPair());
    String side = adaptOrderType(limitOrder.getType());
    return new LgoPlaceLimitOrder(
        0,
        side,
        product,
        limitOrder.getOriginalAmount(),
        limitOrder.getLimitPrice(),
        limitOrder.getTimestamp().toInstant());
  }

  public static LgoPlaceOrder adaptEncryptedMarketOrder(MarketOrder marketOrder) {
    String product = adaptCurrencyPair(marketOrder.getCurrencyPair());
    String side = adaptOrderType(marketOrder.getType());
    return new LgoPlaceMarketOrder(
        0, side, product, marketOrder.getOriginalAmount(), marketOrder.getTimestamp().toInstant());
  }

  public static LgoPlaceOrder adaptEncryptedCancelOrder(String orderId, Date date) {
    return new LgoPlaceCancelOrder(0, orderId, date.toInstant());
  }

  public static LgoUnencryptedOrder adaptUnencryptedLimitOrder(LimitOrder limitOrder) {
    String product = adaptCurrencyPair(limitOrder.getCurrencyPair());
    String side = adaptOrderType(limitOrder.getType());
    return new LgoUnencryptedOrder(
        "L",
        side,
        product,
        limitOrder.getOriginalAmount().toString(),
        limitOrder.getLimitPrice().toString(),
        limitOrder.getTimestamp().getTime());
  }

  public static LgoUnencryptedOrder adaptUnencryptedMarketOrder(MarketOrder marketOrder) {
    String product = adaptCurrencyPair(marketOrder.getCurrencyPair());
    String side = adaptOrderType(marketOrder.getType());
    return new LgoUnencryptedOrder(
        "M",
        side,
        product,
        marketOrder.getOriginalAmount().toString(),
        null,
        marketOrder.getTimestamp().getTime());
  }

  private static String adaptOrderType(OrderType type) {
    return type == OrderType.BID ? "B" : "S";
  }

  public static String adaptCurrencyPair(CurrencyPair currencyPair) {
    return String.format(
        "%s-%s", currencyPair.base.getCurrencyCode(), currencyPair.counter.getCurrencyCode());
  }

  private static UserTrade adaptUserTrade(LgoUserTrade lgoUserTrade) {
    OrderType type = adaptUserTradeType(lgoUserTrade);
    CurrencyPair currencyPair = adaptProductId(lgoUserTrade.getProductId());
    Date creationDate = lgoUserTrade.getCreationDate();
    return new UserTrade.Builder()
        .type(type)
        .originalAmount(lgoUserTrade.getQuantity())
        .currencyPair(currencyPair)
        .price(lgoUserTrade.getPrice())
        .timestamp(creationDate)
        .id(lgoUserTrade.getId())
        .orderId(lgoUserTrade.getOrderId())
        .feeAmount(lgoUserTrade.getFees())
        .feeCurrency(currencyPair.counter)
        .build();
  }

  static CurrencyPair adaptProductId(String productId) {
    String[] pair = productId.split("-");
    return new CurrencyPair(pair[0], pair[1]);
  }

  static OrderType adaptUserTradeType(LgoUserTrade trade) {
    boolean bidSide = buyerTaker(trade) || sellerMaker(trade);
    return bidSide ? OrderType.BID : OrderType.ASK;
  }

  private static boolean sellerMaker(LgoUserTrade trade) {
    return trade.getSide().equals("S") && trade.getLiquidity().equals("M");
  }

  private static boolean buyerTaker(LgoUserTrade trade) {
    return trade.getSide().equals("B") && trade.getLiquidity().equals("T");
  }

  public static UserTrades adaptUserTrades(WithCursor<LgoUserTrades> lastTrades) {
    List<UserTrade> trades =
        lastTrades.getResult().getTrades().stream()
            .map(LgoAdapters::adaptUserTrade)
            .collect(Collectors.toList());
    return new UserTrades(trades, 0L, Trades.TradeSortType.SortByID, lastTrades.getNextPage());
  }

  public static OrderBook adaptOrderBook(LgoOrderbook ob, CurrencyPair pair) {
    List<LimitOrder> bids =
        ob.getBids().stream()
            .map(e -> adaptEntryToLimitOrder(e, OrderType.BID, pair))
            .collect(Collectors.toList());
    List<LimitOrder> asks =
        ob.getAsks().stream()
            .map(e -> adaptEntryToLimitOrder(e, OrderType.ASK, pair))
            .collect(Collectors.toList());

    return new OrderBook(null, asks, bids, true);
  }

  public static LimitOrder adaptEntryToLimitOrder(
      Object[] entry, OrderType bid, CurrencyPair pair) {
    return new LimitOrder(
        bid,
        new BigDecimal(entry[1].toString()),
        pair,
        null,
        null,
        new BigDecimal(entry[0].toString()));
  }

  public static String adaptDateParam(Date date) {
    return ISO_DATE_FORMAT.format(date);
  }
}
