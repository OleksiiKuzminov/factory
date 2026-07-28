package org.folio.factory.app.security;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;

/**
 * Exposes the signed-in user to the server-rendered console without pulling in a
 * Thymeleaf security dialect. Null when nobody is authenticated, so the templates
 * simply omit the session controls.
 *
 * <p>The attribute name must never collide with the layout fragment's {@code title},
 * {@code activeNav}, {@code content} or {@code scripts} parameters, which shadow
 * same-named model attributes.</p>
 */
@ControllerAdvice
public class CurrentUserAdvice {

    @ModelAttribute("currentUser")
    public String currentUser(Principal principal) {
        return principal == null ? null : principal.getName();
    }
}
