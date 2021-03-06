package com.spean.springboot.pac4j.cas.support;

import io.buji.pac4j.filter.CallbackFilter;
import io.buji.pac4j.filter.LogoutFilter;
import io.buji.pac4j.filter.SecurityFilter;
import io.buji.pac4j.subject.Pac4jSubjectFactory;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;

import org.apache.shiro.session.mgt.SessionManager;
import org.apache.shiro.session.mgt.eis.MemorySessionDAO;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.apache.shiro.spring.LifecycleBeanPostProcessor;
import org.apache.shiro.spring.security.interceptor.AuthorizationAttributeSourceAdvisor;
import org.apache.shiro.spring.web.ShiroFilterFactoryBean;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.servlet.SimpleCookie;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.pac4j.cas.client.CasClient;
import org.pac4j.cas.config.CasConfiguration;
import org.pac4j.cas.config.CasProtocol;
import org.pac4j.cas.logout.DefaultCasLogoutHandler;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.WebContext;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.Ordered;
import org.springframework.web.filter.DelegatingFilterProxy;

@Configuration
@EnableConfigurationProperties(CasClientConfigurationProperties.class)
public class ShiroConfig {

	@Autowired
	CasClientConfigurationProperties casClientConfigurationProperties;
    
    
    @Bean()
    public Config config(CasClient casClient, CustomShiroSessionStore shiroSessionStore) {
        Config config = new Config(casClient);
        config.setSessionStore(shiroSessionStore);
        return config;
    }

    /**
     * 自定义存储
     * @return
     */
    @Bean
    public CustomShiroSessionStore shiroSessionStore(){
        return CustomShiroSessionStore.INSTANCE;
    }

    /**
     * cas 客户端配置
     * @param casConfig
     * @return
     */
    @Bean
    public CasClient casClient(CasConfiguration casConfig){
        CasClient casClient = new CustomCasClient(casConfig);
        //客户端回调地址
        casClient.setCallbackUrl(casClientConfigurationProperties.getProjectUrl() + "/callback?client_name=" + casClientConfigurationProperties.getClientName());
        casClient.setName(casClientConfigurationProperties.getClientName());
        return casClient;
    }

    /**
     * 请求cas服务端配置
     * @param casLogoutHandler
     */
    @Bean
    public CasConfiguration casConfig(){
        final CasConfiguration configuration = new CasConfiguration();
        //CAS server登录地址
        configuration.setLoginUrl(casClientConfigurationProperties.getCasServerUrl() + "/login");
        //CAS 版本，默认为 CAS30，我们使用的是 CAS20
        configuration.setProtocol(CasProtocol.CAS20);
        configuration.setAcceptAnyProxy(true);
        configuration.setPrefixUrl(casClientConfigurationProperties.getCasServerUrl() + "/");
        configuration.setLogoutHandler(new DefaultCasLogoutHandler<WebContext>());
        return configuration;
    }


    
    
    @Bean("securityManager")
    public DefaultWebSecurityManager securityManager(Pac4jSubjectFactory subjectFactory, SessionManager sessionManager, CasRealm casRealm){
        DefaultWebSecurityManager manager = new DefaultWebSecurityManager();
        manager.setRealm(casRealm);
        manager.setSubjectFactory(subjectFactory);
        manager.setSessionManager(sessionManager);
        return manager;
    }
    @Bean
    public CasRealm casRealm(){
        CasRealm realm = new CasRealm();
        // 使用自定义的realm
        realm.setClientName(casClientConfigurationProperties.getClientName());
        realm.setCachingEnabled(false);
        //暂时不使用缓存
        realm.setAuthenticationCachingEnabled(false);
        realm.setAuthorizationCachingEnabled(false);
        //realm.setAuthenticationCacheName("authenticationCache");
        //realm.setAuthorizationCacheName("authorizationCache");
        return realm;
    }
    /**
     * 使用 pac4j 的 subjectFactory
     * @return
     */
    @Bean
    public Pac4jSubjectFactory subjectFactory(){
        return new Pac4jSubjectFactory();
    }

    @Bean
    public FilterRegistrationBean<DelegatingFilterProxy> filterRegistrationBean() {
        FilterRegistrationBean<DelegatingFilterProxy> filterRegistration = new FilterRegistrationBean<DelegatingFilterProxy>();
        filterRegistration.setFilter(new DelegatingFilterProxy("shiroFilter"));
        //  该值缺省为false,表示生命周期由SpringApplicationContext管理,设置为true则表示由ServletContainer管理
        filterRegistration.addInitParameter("targetFilterLifecycle", "true");
        filterRegistration.setEnabled(true);
        filterRegistration.addUrlPatterns("/*");
        filterRegistration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.FORWARD);
        return filterRegistration;
    }

    /**
     * 加载shiroFilter权限控制规则（从数据库读取然后配置）
     * @param shiroFilterFactoryBean
     */
    private void loadShiroFilterChain(ShiroFilterFactoryBean shiroFilterFactoryBean){
        /*下面这些规则配置最好配置到配置文件中 */
        // filterChainDefinitionMap.put("/user/edit/**", "authc,perms[user:edit]");
        shiroFilterFactoryBean.setFilterChainDefinitionMap(casClientConfigurationProperties.getFilterChainDefinitionMap());
    }

    
    

    /**
     * shiroFilter
     * @param securityManager
     * @param config
     * @return
     */
    @Bean("shiroFilter")
    public ShiroFilterFactoryBean factory(DefaultWebSecurityManager securityManager, Config config) {
        ShiroFilterFactoryBean shiroFilterFactoryBean = new ShiroFilterFactoryBean();
        // 必须设置 SecurityManager
        shiroFilterFactoryBean.setSecurityManager(securityManager);
        //shiroFilterFactoryBean.setUnauthorizedUrl("/403");
        // 添加casFilter到shiroFilter中
        loadShiroFilterChain(shiroFilterFactoryBean);
        Map<String, Filter> filters = new HashMap<>(4);
        //cas 资源认证拦截器
        SecurityFilter securityFilter = new SecurityFilter();
        securityFilter.setConfig(config);
        securityFilter.setClients(casClientConfigurationProperties.getClientName());
        filters.put("securityFilter", securityFilter);
       //cas 自定义资源认证拦截器--允许未登录返回，但是如果在其他项目中已经登录的（cookie中已经包含了tgc）又需要他能够显示用户信息
        CustomCasFilter customCasFilter = new CustomCasFilter();
        customCasFilter.setConfig(config);
        customCasFilter.setClients(casClientConfigurationProperties.getClientName());
        filters.put("customCasFilter", customCasFilter);
        //cas 认证后回调拦截器
        CallbackFilter callbackFilter = new CustomCallbackFilter();
        callbackFilter.setConfig(config);
        callbackFilter.setDefaultUrl(casClientConfigurationProperties.getProjectUrl());
        filters.put("callbackFilter", callbackFilter);
        // 注销 拦截器
        LogoutFilter logoutFilter = new LogoutFilter();
        logoutFilter.setConfig(config);
        logoutFilter.setCentralLogout(true);
        logoutFilter.setLocalLogout(true);
        //添加logout后  跳转到指定url  url的匹配规则  默认为 /.*;  
        logoutFilter.setLogoutUrlPattern(".*");
        logoutFilter.setDefaultUrl(casClientConfigurationProperties.getProjectUrl() + "/callback?client_name=" + casClientConfigurationProperties.getClientName());
        filters.put("logout",logoutFilter);
        shiroFilterFactoryBean.setFilters(filters);
        return shiroFilterFactoryBean;
    }

    @Bean
    public SessionDAO sessionDAO(){
        return new MemorySessionDAO();
    }

    /**
     * 自定义cookie名称
     * @return
     */
    @Bean
    public SimpleCookie sessionIdCookie(){
        SimpleCookie cookie = new SimpleCookie("sid");
        cookie.setMaxAge(-1);
        cookie.setPath("/");
        cookie.setHttpOnly(false);
        return cookie;
    }

    @Bean
    public DefaultWebSessionManager sessionManager(SimpleCookie sessionIdCookie, SessionDAO sessionDAO){
        DefaultWebSessionManager sessionManager = new DefaultWebSessionManager();
        sessionManager.setSessionIdCookie(sessionIdCookie);
        sessionManager.setSessionIdCookieEnabled(true);
        //30分钟
        sessionManager.setGlobalSessionTimeout(180000);
        sessionManager.setSessionDAO(sessionDAO);
        sessionManager.setDeleteInvalidSessions(true);
        sessionManager.setSessionValidationSchedulerEnabled(true);
        return sessionManager;
    }

    /**
     * 下面的代码是添加注解支持
     */
    @Bean
    @DependsOn("lifecycleBeanPostProcessor")
    public DefaultAdvisorAutoProxyCreator defaultAdvisorAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator defaultAdvisorAutoProxyCreator = new DefaultAdvisorAutoProxyCreator();
        defaultAdvisorAutoProxyCreator.setProxyTargetClass(true);
        return defaultAdvisorAutoProxyCreator;
    }

    @Bean
    public static LifecycleBeanPostProcessor lifecycleBeanPostProcessor() {
        return new LifecycleBeanPostProcessor();
    }

    @Bean
    public AuthorizationAttributeSourceAdvisor authorizationAttributeSourceAdvisor(DefaultWebSecurityManager securityManager) {
        AuthorizationAttributeSourceAdvisor advisor = new AuthorizationAttributeSourceAdvisor();
        advisor.setSecurityManager(securityManager);
        return advisor;
    }
    
    @Bean
    public FilterRegistrationBean<CustomContextThreadLocalFilter> casAssertionThreadLocalFilter(ShiroFilterFactoryBean shiroFilterFactoryBean) {
    	/**
    	 * 所有经过身份过滤拦截的请求、都需要经过CustomAssertionThreadLocalFilter 这个过滤器、
    	 */
    	Map<String, String> filterChainDefinitionMap = shiroFilterFactoryBean.getFilterChainDefinitionMap();
    	List<String> casUrls = new LinkedList<String>();
    	for (Entry<String, String> entry : filterChainDefinitionMap.entrySet()) {
			if("securityFilter".equals(entry.getValue())||"customCasFilter".equals(entry.getValue())){
				casUrls.add(entry.getKey());
			}
		}
        final FilterRegistrationBean<CustomContextThreadLocalFilter> assertionTLFilter = new FilterRegistrationBean<CustomContextThreadLocalFilter>();
        assertionTLFilter.setFilter(new CustomContextThreadLocalFilter());
        assertionTLFilter.setOrder(Ordered.LOWEST_PRECEDENCE);
        assertionTLFilter.setUrlPatterns(casUrls);
        return assertionTLFilter;
    }

}
