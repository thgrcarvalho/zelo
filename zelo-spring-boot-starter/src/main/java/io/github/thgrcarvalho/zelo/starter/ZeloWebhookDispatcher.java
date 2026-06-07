package io.github.thgrcarvalho.zelo.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers {@link ZeloWebhook} handler methods across the application's beans
 * (after all singletons are instantiated) and dispatches events to them.
 */
public class ZeloWebhookDispatcher implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ZeloWebhookDispatcher.class);

    private final ConfigurableListableBeanFactory beanFactory;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();

    public ZeloWebhookDispatcher(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = beanFactory.getType(beanName);
            } catch (RuntimeException e) {
                continue; // unresolvable bean type — skip
            }
            if (type == null) {
                continue;
            }
            for (Method method : type.getMethods()) {
                ZeloWebhook annotation = AnnotationUtils.findAnnotation(method, ZeloWebhook.class);
                if (annotation != null) {
                    validateSignature(method);
                    handlers.put(annotation.value(), new Handler(beanFactory.getBean(beanName), method));
                    log.info("Registered @ZeloWebhook handler {}#{} for event '{}'",
                            type.getSimpleName(), method.getName(), annotation.value());
                }
            }
        }
    }

    /**
     * Invoke the handler for {@code eventType}. Returns the handler's return value
     * (the fulfillment proof), or {@code null} for a void handler.
     *
     * @throws NoHandlerRegisteredException if no handler is registered
     * @throws Exception                    whatever the handler throws (erasure failure)
     */
    public Object dispatch(String eventType, ZeloDeletionRequest event) throws Exception {
        Handler handler = handlers.get(eventType);
        if (handler == null) {
            throw new NoHandlerRegisteredException(eventType);
        }
        Object[] args = handler.method().getParameterCount() == 0 ? new Object[0] : new Object[]{event};
        try {  // signature validated at registration (see validateSignature)
            return handler.method().invoke(handler.bean(), args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException("Webhook handler failed", cause);
        }
    }

    private static void validateSignature(Method method) {
        Class<?>[] params = method.getParameterTypes();
        boolean ok = params.length == 0
                || (params.length == 1 && params[0].isAssignableFrom(ZeloDeletionRequest.class));
        if (!ok) {
            throw new IllegalStateException("@ZeloWebhook method " + method.getDeclaringClass().getName()
                    + "#" + method.getName() + " must take no arguments or a single "
                    + ZeloDeletionRequest.class.getSimpleName() + " parameter");
        }
    }

    private record Handler(Object bean, Method method) {
    }
}
