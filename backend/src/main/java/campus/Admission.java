package campus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class Admission implements WebMvcConfigurer {
    // 单实例演示环境的写入并发预算。多实例部署需配置统一入口限流。
    final Semaphore slots=new Semaphore(16);
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                String requestId=java.util.UUID.randomUUID().toString();
                response.setHeader("X-Request-Id",requestId);
                if ("GET".equals(request.getMethod())) return true;
                if (!slots.tryAcquire()) throw new ApiError(429,"RATE_LIMITED","系统繁忙，请稍后重试");
                request.setAttribute("campus-slot",true);
                return true;
            }
            public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) {
                if (Boolean.TRUE.equals(request.getAttribute("campus-slot"))) slots.release();
            }
        }).addPathPatterns("/api/**");
    }
}
