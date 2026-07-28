package org.folio.factory.app.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class LoginControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new LoginController())
                .setViewResolvers(new InternalResourceViewResolver("/templates/", ".html"))
                .build();
    }

    @Test
    void login_plainRequest_rendersTheFormWithNeitherNotice() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("loginFailed", false))
                .andExpect(model().attribute("loggedOut", false));
    }

    @Test
    void login_errorParameter_flagsTheFailedAttempt() throws Exception {
        mvc.perform(get("/login").param("error", ""))
                .andExpect(model().attribute("loginFailed", true))
                .andExpect(model().attribute("loggedOut", false));
    }

    @Test
    void login_logoutParameter_flagsTheSignOut() throws Exception {
        mvc.perform(get("/login").param("logout", ""))
                .andExpect(model().attribute("loginFailed", false))
                .andExpect(model().attribute("loggedOut", true));
    }
}
