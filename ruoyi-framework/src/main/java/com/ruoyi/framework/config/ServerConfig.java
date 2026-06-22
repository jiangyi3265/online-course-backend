package com.ruoyi.framework.config;

import javax.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.ServletUtils;

/**
 * 服务相关配置
 * 
 * @author ruoyi
 */
@Component
public class ServerConfig
{
    /**
     * 获取完整的请求路径，包括：域名，端口，上下文访问路径
     * 
     * @return 服务地址
     */
    public String getUrl()
    {
        HttpServletRequest request = ServletUtils.getRequest();
        return getDomain(request);
    }

    public static String getDomain(HttpServletRequest request)
    {
        String proto = firstForwardedValue(request.getHeader("X-Forwarded-Proto"));
        String host = firstForwardedValue(request.getHeader("X-Forwarded-Host"));
        if (isBlank(host))
        {
            host = firstForwardedValue(request.getHeader("Host"));
        }
        if (!isBlank(proto) && !isBlank(host))
        {
            String port = firstForwardedValue(request.getHeader("X-Forwarded-Port"));
            if (!isBlank(port) && host.indexOf(':') < 0 && !isDefaultPort(proto, port))
            {
                host = host + ":" + port;
            }
            return proto + "://" + host + request.getServletContext().getContextPath();
        }
        StringBuffer url = request.getRequestURL();
        String contextPath = request.getServletContext().getContextPath();
        return url.delete(url.length() - request.getRequestURI().length(), url.length()).append(contextPath).toString();
    }

    private static String firstForwardedValue(String value)
    {
        if (value == null)
        {
            return "";
        }
        int index = value.indexOf(',');
        return (index >= 0 ? value.substring(0, index) : value).trim();
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().length() == 0;
    }

    private static boolean isDefaultPort(String proto, String port)
    {
        return ("http".equalsIgnoreCase(proto) && "80".equals(port)) || ("https".equalsIgnoreCase(proto) && "443".equals(port));
    }
}
