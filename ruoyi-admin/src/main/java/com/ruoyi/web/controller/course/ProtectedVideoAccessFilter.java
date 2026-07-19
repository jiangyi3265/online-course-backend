package com.ruoyi.web.controller.course;

import java.io.IOException;
import java.util.Locale;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Prevents uploaded source videos from being fetched through RuoYi's public
 * /profile resource mapping. Course playback is available only through the
 * encrypted, token-checked HLS endpoints.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ProtectedVideoAccessFilter extends OncePerRequestFilter
{
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException
    {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI().toLowerCase(Locale.ROOT);
        boolean uploadedResource = uri.matches("^/(?:prod-api/)?(?:profile|avatar|upload|uploads)/.+");
        boolean rawVideo = uri.matches(".+\\.(?:mp4|m4v|mov|webm|avi|mkv|flv|wmv)$");
        if (uploadedResource && rawVideo)
        {
            response.setHeader("Cache-Control", "private, no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Download-Options", "noopen");
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "原始视频地址不可直接访问");
            return;
        }
        chain.doFilter(request, response);
    }
}
