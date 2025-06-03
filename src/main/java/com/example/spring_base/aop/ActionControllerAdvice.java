package com.example.spring_base.aop;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Aspect
@Configuration
@RequiredArgsConstructor
public class ActionControllerAdvice {

    private static final Logger logger = LoggerFactory.getLogger(ActionControllerAdvice.class);

    private final HttpServletRequest request;

    @Pointcut("target(com.example.inventory_management.core.controller.CoreController) && " +
            "(@annotation(org.springframework.web.bind.annotation.RequestMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.GetMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PostMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PutMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.DeleteMapping) || " +
            "@annotation(org.springframework.web.bind.annotation.PatchMapping))")
    public void coreControllerMethods() {
    }

    @Around("coreControllerMethods()")
    public Object preHandleAction(ProceedingJoinPoint pjp) throws Throwable {
        try {
            Object target = pjp.getTarget();

            CoreController<?> actionController = (CoreController<?>) target;
            String methodName = pjp.getSignature().getName();

            String functionId = CoreUtils.getFunctionId(actionController);
            String screenId = CoreUtils.getScreenId(actionController);

            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            Method method = CoreUtils.getMethod(actionController.getClass(), methodName);
            String eventAuthority = getEventAuthority(method);

            if (!CoreConst.NO_AUTHORITY.equals(eventAuthority)) {
                String authorityToCheck = String.format("%s:%s", functionId, eventAuthority);

                if (authentication != null && authentication.isAuthenticated()) {
                    boolean hasAuthority = authentication.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .anyMatch(authorityToCheck::equals);

                    if (!hasAuthority) {
                        throw new SecurityException("User does not have the required authority: " + authorityToCheck);
                    }
                } else {
                    throw new SecurityException("User is not authenticated");
                }
            }

            // Set RequestScopeInfoMgr eventId safely
            String url = request.getRequestURL() != null ? request.getRequestURL().toString() : "";
            RequestScopeInfoMgr.setEventId(CoreUtils.getEventId(url));

            // Validate if first argument is CoreForm
            Object[] args = pjp.getArgs();
            CoreForm form = null;
            if (args.length > 0 && args[0] instanceof CoreForm) {
                form = (CoreForm) args[0];
            }

            if (form != null && !executeValidate(actionController, form)) {
                return CoreConst.SUCCESS;
            }

            // Proceed with original method
            return pjp.proceed();

        } catch (SecurityException se) {
            logger.warn("Security exception: {}", se.getMessage());
            throw se; // or handle accordingly, e.g., return HTTP 403 response object
        } catch (Exception e) {
            logger.error("Exception in method {}: {}", pjp.getSignature().getName(), e.getMessage(), e);
            // Optionally wrap or rethrow exception
            throw e;
        }
    }

    private String getEventAuthority(Method method) {
        if (method.getAnnotation(AuthorityUpdateEvent.class) != null) {
            return CoreConst.AUTHORITY_UPDATE;
        } else if (method.getAnnotation(NoAuthorityRequiredEvent.class) != null) {
            return CoreConst.NO_AUTHORITY;
        } else {
            return CoreConst.AUTHORITY_READ;
        }
    }

    private boolean executeValidate(CoreController<?> actionController, CoreForm form) {
        CoreUtils.invokeMethod(actionController, "validate", form);
        CoreUtils.invokeMethod(actionController, "verifyStrictly", form);
        return !actionController.hasErrors();
    }
}
