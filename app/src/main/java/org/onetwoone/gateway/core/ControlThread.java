package org.onetwoone.gateway.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code that may only run on the {@link GatewayControlThread}.
 *
 * <p>A marker for readers and reviewers, nothing more - it has no runtime presence. The
 * enforcement is {@link GatewayControlThread#assertOnControlThread(String)}, which every
 * method carrying this annotation must call as its first statement.
 *
 * <p>On a field or a type it means "this state is confined to the control thread": readers
 * and writers must both be on it, or must go through an immutable snapshot
 * ({@link GatewayStatus}).
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.TYPE})
public @interface ControlThread {
}
