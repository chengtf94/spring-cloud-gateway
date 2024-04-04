package org.springframework.cloud.gateway.filter.ratelimit;

import java.util.Collections;
import java.util.Map;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.support.StatefulConfigurable;
import org.springframework.util.Assert;

/**
 * 限流器接口
 *
 * @author Spencer Gibb
 */
public interface RateLimiter<C> extends StatefulConfigurable<C> {

	/** 是否允许请求通过：routeId为路由ID */
	Mono<Response> isAllowed(String routeId, String id);

	/** 响应类 */
	class Response {

		/** 是否允许通过、剩余的令牌数、请求头Map */
		private final boolean allowed;
		private final long tokensRemaining;
		private final Map<String, String> headers;

		public Response(boolean allowed, Map<String, String> headers) {
			this.allowed = allowed;
			this.tokensRemaining = -1;
			Assert.notNull(headers, "headers may not be null");
			this.headers = headers;
		}

		public boolean isAllowed() {
			return allowed;
		}

		public Map<String, String> getHeaders() {
			return Collections.unmodifiableMap(headers);
		}

		@Override
		public String toString() {
			final StringBuffer sb = new StringBuffer("Response{");
			sb.append("allowed=").append(allowed);
			sb.append(", headers=").append(headers);
			sb.append(", tokensRemaining=").append(tokensRemaining);
			sb.append('}');
			return sb.toString();
		}

	}

}
