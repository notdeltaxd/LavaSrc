package com.github.topi314.lavasrc.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.lavasrc.jiosaavn")
@Component
public class JioSaavnConfig {
	private String apiUrl;
	private int searchLimit = 5;
	private int recommendationsLimit = 5;

	public String getApiUrl() {
		return this.apiUrl;
	}

	public void setApiUrl(String apiUrl) {
		this.apiUrl = apiUrl;
	}

	public int getSearchLimit() {
		return this.searchLimit;
	}

	public void setSearchLimit(int searchLimit) {
		this.searchLimit = searchLimit;
	}

	public int getRecommendationsLimit() {
		return this.recommendationsLimit;
	}

	public void setRecommendationsLimit(int recommendationsLimit) {
		this.recommendationsLimit = recommendationsLimit;
	}
}
