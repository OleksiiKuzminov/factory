package org.folio.factory.app.web;

import org.folio.factory.core.domain.AuditEvent;
import org.folio.factory.core.domain.AuditEventType;
import org.folio.factory.core.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {

    private static final UUID EXEC_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private AuditEventRepository audit;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AuditController(audit, json))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private AuditEvent event(long id, AuditEventType type, String detail) {
        AuditEvent event = new AuditEvent(EXEC_ID, type, "test-spec", "engine", detail);
        try {
            var field = AuditEvent.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(event, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return event;
    }

    @Test
    void list_noFilter_usesFindAllAndParsesDetailJson() throws Exception {
        AuditEvent event = event(7L, AuditEventType.CONNECTOR_ACTION, "{\"connector\":\"github\"}");
        when(audit.findAllByOrderByIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 50), 1));

        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(7))
                .andExpect(jsonPath("$.items[0].executionId").value(EXEC_ID.toString()))
                .andExpect(jsonPath("$.items[0].eventType").value("CONNECTOR_ACTION"))
                .andExpect(jsonPath("$.items[0].stepId").value("test-spec"))
                .andExpect(jsonPath("$.items[0].actor").value("engine"))
                .andExpect(jsonPath("$.items[0].detail.connector").value("github"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));

        verify(audit).findAllByOrderByIdDesc(any(Pageable.class));
    }

    @Test
    void list_nullDetail_serialisesAsNull() throws Exception {
        AuditEvent event = event(3L, AuditEventType.STEP_STARTED, null);
        when(audit.findAllByOrderByIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 50), 1));

        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].detail").value(nullValue()));
    }

    @Test
    void list_withEventType_usesTypedFinder() throws Exception {
        AuditEvent event = event(9L, AuditEventType.CONNECTOR_SKIPPED, "{\"connector\":\"jira\"}");
        when(audit.findByEventTypeOrderByIdDesc(eq(AuditEventType.CONNECTOR_SKIPPED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 50), 1));

        mvc.perform(get("/api/audit").param("eventType", "CONNECTOR_SKIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("CONNECTOR_SKIPPED"));

        verify(audit).findByEventTypeOrderByIdDesc(eq(AuditEventType.CONNECTOR_SKIPPED), any(Pageable.class));
    }

    @Test
    void list_connectorFailedEventType_isAKnownFilterValue() throws Exception {
        AuditEvent event = event(11L, AuditEventType.CONNECTOR_FAILED,
                "{\"connector\":\"testrail\",\"detail\":\"500 from TestRail\"}");
        when(audit.findByEventTypeOrderByIdDesc(eq(AuditEventType.CONNECTOR_FAILED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 50), 1));

        mvc.perform(get("/api/audit").param("eventType", "CONNECTOR_FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("CONNECTOR_FAILED"))
                .andExpect(jsonPath("$.items[0].detail.connector").value("testrail"));
    }

    @Test
    void list_badEventType_unprocessableWithoutRepositoryAccess() throws Exception {
        mvc.perform(get("/api/audit").param("eventType", "NOPE"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error").value(containsString("NOPE")));

        verifyNoInteractions(audit);
    }

    @Test
    void list_sizeTooLarge_unprocessable() throws Exception {
        mvc.perform(get("/api/audit").param("size", "201"))
                .andExpect(status().is(422));
        verifyNoInteractions(audit);
    }
}
