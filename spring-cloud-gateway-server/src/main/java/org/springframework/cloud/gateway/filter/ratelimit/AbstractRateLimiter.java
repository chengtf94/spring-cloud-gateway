package org.springframework.cloud.gateway.filter.ratelimit;

import java.util.Map;

import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.support.AbstractStatefulConfigurable;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.ApplicationListener;
import org.springframework.core.style.ToStringCreator;

/**
 * 限流器基类
 *
 * @author Spencer Gibb
 */
public abstract class AbstractRateLimiter<C> extends AbstractStatefulConfigurable<C>
		implements RateLimiter<C>, ApplicationListener<FilterArgsEvent> {

	/** 配置属性名称、配置Service */
	private String configurationPropertyName;
	private ConfigurationService configurationService;
	protected String getConfigurationPropertyName() {
		return configurationPropertyName;
	}
	protected void setConfigurationService(ConfigurationService configurationService) {
		this.configurationService = configurationService;
	}

	/** 构造方法 */
	protected AbstractRateLimiter(Class<C> configClass, String configurationPropertyName,
			ConfigurationService configurationService) {
		super(configClass);
		this.configurationPropertyName = configurationPropertyName;
		this.configurationService = configurationService;
	}

	/** 监听过滤器参数事件 */
	@Override
	public void onApplicationEvent(FilterArgsEvent event) {
		Map<String, Object> args = event.getArgs();
		if (args.isEmpty() || !hasRelevantKey(args)) {
			return;
		}
		String routeId = event.getRouteId();
		C routeConfig = newConfig();
		if (this.configurationService != null) {
			this.configurationService
					.with(routeConfig)
					.name(this.configurationPropertyName)
					.normalizedProperties(args)
					.bind();
		}
		getConfig().put(routeId, routeConfig);
	}

	private boolean hasRelevantKey(Map<String, Object> args) {
		return args.keySet().stream().anyMatch(key -> key.startsWith(configurationPropertyName + "."));
	}

	@Override
	public String toString() {
		return new ToStringCreator(this).append("configurationPropertyName", configurationPropertyName)
				.append("config", getConfig()).append("configClass", getConfigClass()).toString();
	}

}
