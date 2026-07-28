package org.folio.factory.app.security;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The one console page an unauthenticated visitor may see. It deliberately does not
 * use the shared layout: the navigation would only offer links they cannot follow.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@RequestParam(name = "error", required = false) String error,
                        @RequestParam(name = "logout", required = false) String logout,
                        Model model) {
        model.addAttribute("loginFailed", error != null);
        model.addAttribute("loggedOut", logout != null);
        return "login";
    }
}
