package org.springframework.cloud.gateway.filter.ratelimit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.validation.constraints.Min;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.style.ToStringCreator;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

/**
 * Redis限流器
 *
 * @author Spencer Gibb
 * @author Ronny Bräunlich
 * @author Denis Cutic
 */
@ConfigurationProperties("spring.cloud.gateway.redis-rate-limiter")
public class RedisRateLimiter extends AbstractRateLimiter<RedisRateLimiter.Config> implements ApplicationContextAware {
	private Log log = LogFactory.getLog(getClass());

	/** Redis令牌配置名称、Redis lua脚本名称*/
	public static final String CONFIGURATION_PROPERTY_NAME = "redis-rate-limiter";
	public static final String REDIS_SCRIPT_NAME = "redisRequestRateLimiterScript";

	/** 限流器头部信息名称：剩余令牌数、令牌生成速率、突发容量（令牌桶容量）、每个请求消耗的令牌数 */
	public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
	public static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";
	public static final String BURST_CAPACITY_HEADER = "X-RateLimit-Burst-Capacity";
	public static final String REQUESTED_TOKENS_HEADER = "X-RateLimit-Requested-Tokens";

	/** Redis组件、Redis脚本、是否初始化完成、默认配置 */
	private ReactiveStringRedisTemplate redisTemplate;
	private RedisScript<List<Long>> script;
	private AtomicBoolean initialized = new AtomicBoolean(false);
	private Config defaultConfig;

	/** 是否包含限流器头部信息、限流器头部信息名称：剩余令牌数、令牌生成速率、突发容量（令牌桶容量）、每个请求消耗的令牌数 */
	private boolean includeHeaders = true;
	private String remainingHeader = REMAINING_HEADER;
	private String replenishRateHeader = REPLENISH_RATE_HEADER;
	private String burstCapacityHeader = BURST_CAPACITY_HEADER;
	private String requestedTokensHeader = REQUESTED_TOKENS_HEADER;

	/** 构造方法 */
	public RedisRateLimiter(ReactiveStringRedisTemplate redisTemplate, RedisScript<List<Long>> script,
			ConfigurationService configurationService) {
		super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
		this.redisTemplate = redisTemplate;
		this.script = script;
		this.initialized.compareAndSet(false, true);
	}
	public RedisRateLimiter(int defaultReplenishRate, int defaultBurstCapacity) {
		super(Config.class, CONFIGURATION_PROPERTY_NAME, (ConfigurationService) null);
		this.defaultConfig = new Config().setReplenishRate(defaultReplenishRate).setBurstCapacity(defaultBurstCapacity);
	}
	public RedisRateLimiter(int defaultReplenishRate, int defaultBurstCapacity, int defaultRequestedTokens) {
		this(defaultReplenishRate, defaultBurstCapacity);
		this.defaultConfig.setRequestedTokens(defaultRequestedTokens);
	}

	/** 设置应用上下文 */
	@Override
	@SuppressWarnings("unchecked")
	public void setApplicationContext(ApplicationContext context) throws BeansException {
		if (initialized.compareAndSet(false, true)) {
			if (this.redisTemplate == null) {
				this.redisTemplate = context.getBean(ReactiveStringRedisTemplate.class);
			}
			this.script = context.getBean(REDIS_SCRIPT_NAME, RedisScript.class);
			if (context.getBeanNamesForType(ConfigurationService.class).length > 0) {
				setConfigurationService(context.getBean(ConfigurationService.class));
			}
		}
	}

	/** 是否允许请求通过：routeId为路由ID，基于令牌桶算法、Redis lua脚本 */
	@Override
	@SuppressWarnings("unchecked")
	public Mono<Response> isAllowed(String routeId, String id) {
		if (!this.initialized.get()) {
			throw new IllegalStateException("RedisRateLimiter is not initialized");
		}

		// 根据路由ID加载配置
		Config routeConfig = loadConfiguration(routeId);

		// 获取令牌生成速率（每秒）、突发容量（令牌桶容量）、每个请求消耗的令牌数
		int replenishRate = routeConfig.getReplenishRate();
		int burstCapacity = routeConfig.getBurstCapacity();
		int requestedTokens = routeConfig.getRequestedTokens();

		try {

			// 获取Redis缓存key：令牌key + 时间戳key
			List<String> keys = getKeys(id);

			// 执行Redis lua脚本
			List<String> scriptArgs = Arrays.asList(replenishRate + "", burstCapacity + "", "", requestedTokens + "");
			Flux<List<Long>> flux = this.redisTemplate.execute(this.script, keys, scriptArgs);
			return flux.onErrorResume(throwable -> {
				// 执行异常
				if (log.isDebugEnabled()) {
					log.debug("Error calling rate limiter lua", throwable);
				}
				return Flux.just(Arrays.asList(1L, -1L));
			}).reduce(new ArrayList<Long>(), (longs, l) -> {
				longs.addAll(l);
				return longs;
			}).map(results -> {
				// 执行完成，获取限流结果、剩余的令牌数
				boolean allowed = results.get(0) == 1L;
				Long tokensLeft = results.get(1);
				Response response = new Response(allowed, getHeaders(routeConfig, tokensLeft));
				if (log.isDebugEnabled()) {
					log.debug("response: " + response);
				}
				return response;
			});
		} catch (Exception e) {
			log.error("Error determining if user allowed from redis", e);
		}
		return Mono.just(new Response(true, getHeaders(routeConfig, -1L)));
	}

	/** 获取限流器头部信息Map */
	public Map<String, String> getHeaders(Config config, Long tokensLeft) {
		Map<String, String> headers = new HashMap<>();
		if (isIncludeHeaders()) {
			headers.put(this.remainingHeader, tokensLeft.toString());
			headers.put(this.replenishRateHeader, String.valueOf(config.getReplenishRate()));
			headers.put(this.burstCapacityHeader, String.valueOf(config.getBurstCapacity()));
			headers.put(this.requestedTokensHeader, String.valueOf(config.getRequestedTokens()));
		}
		return headers;
	}

	/** 获取Redis缓存key：令牌key + 时间戳key */
	static List<String> getKeys(String id) {
		String prefix = "request_rate_limiter.{" + id;
		String tokenKey = prefix + "}.tokens";
		String timestampKey = prefix + "}.timestamp";
		return Arrays.asList(tokenKey, timestampKey);
	}

	/** 根据路由ID加载配置 */
	Config loadConfiguration(String routeId) {
		Config routeConfig = getConfig().getOrDefault(routeId, defaultConfig);
		if (routeConfig == null) {
			routeConfig = getConfig().get(RouteDefinitionRouteLocator.DEFAULT_FILTERS);
		}
		if (routeConfig == null) {
			throw new IllegalArgumentException("No Configuration found for route " + routeId + " or defaultFilters");
		}
		return routeConfig;
	}

	/** 配置类 */
	@Validated
	public static class Config {

		/** 令牌生成速率（每秒）、突发容量（令牌桶容量）、每个请求消耗的令牌数 */
		@Min(1)
		private int replenishRate;
		@Min(0)
		private int burstCapacity = 1;
		@Min(1)
		private int requestedTokens = 1;

		public int getReplenishRate() {
			return replenishRate;
		}

		public Config setReplenishRate(int replenishRate) {
			this.replenishRate = replenishRate;
			return this;
		}

		public int getBurstCapacity() {
			return burstCapacity;
		}

		public Config setBurstCapacity(int burstCapacity) {
			Assert.isTrue(burstCapacity >= this.replenishRate, "BurstCapacity(" + burstCapacity
					+ ") must be greater than or equal than replenishRate(" + this.replenishRate + ")");
			this.burstCapacity = burstCapacity;
			return this;
		}

		public int getRequestedTokens() {
			return requestedTokens;
		}

		public Config setRequestedTokens(int requestedTokens) {
			this.requestedTokens = requestedTokens;
			return this;
		}

		@Override
		public String toString() {
			return new ToStringCreator(this).append("replenishRate", replenishRate)
					.append("burstCapacity", burstCapacity).append("requestedTokens", requestedTokens).toString();

		}

	}

	Config getDefaultConfig() {
		return defaultConfig;
	}

	public boolean isIncludeHeaders() {
		return includeHeaders;
	}

	public void setIncludeHeaders(boolean includeHeaders) {
		this.includeHeaders = includeHeaders;
	}

	public String getRemainingHeader() {
		return remainingHeader;
	}

	public void setRemainingHeader(String remainingHeader) {
		this.remainingHeader = remainingHeader;
	}

	public String getReplenishRateHeader() {
		return replenishRateHeader;
	}

	public void setReplenishRateHeader(String replenishRateHeader) {
		this.replenishRateHeader = replenishRateHeader;
	}

	public String getBurstCapacityHeader() {
		return burstCapacityHeader;
	}

	public void setBurstCapacityHeader(String burstCapacityHeader) {
		this.burstCapacityHeader = burstCapacityHeader;
	}

	public String getRequestedTokensHeader() {
		return requestedTokensHeader;
	}

	public void setRequestedTokensHeader(String requestedTokensHeader) {
		this.requestedTokensHeader = requestedTokensHeader;
	}

}
