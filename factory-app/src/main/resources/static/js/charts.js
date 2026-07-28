(function () {
  if (typeof Chart === 'undefined') return;
  var grid = document.querySelector('.charts-grid');
  if (!grid) return;

  // ---------- readTokens ----------
  // Resolve design-system CSS custom properties (HSL triplets) into hsl() strings
  // so chart colors track the active light/dark theme.
  var T = {};

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function hsl(name) {
    var v = cssVar(name);
    return v ? 'hsl(' + v + ')' : '#888888';
  }

  function readTokens() {
    T = {
      chart1: hsl('--chart-1'),
      chart2: hsl('--chart-2'),
      chart3: hsl('--chart-3'),
      chart4: hsl('--chart-4'),
      chart5: hsl('--chart-5'),
      success: hsl('--success'),
      warning: hsl('--warning'),
      destructive: hsl('--destructive'),
      muted: hsl('--muted-foreground'),
      border: hsl('--border'),
      foreground: hsl('--foreground'),
      card: hsl('--card')
    };
  }

  // ---------- STATUS_COLORS (color follows the entity, never position) ----------
  function statusColor(status) {
    switch (status) {
      case 'PENDING': return T.chart1;
      case 'RUNNING': return T.chart2;
      case 'AWAITING_HITL': return T.warning;
      case 'AWAITING_SUBFLOW': return T.chart4;
      case 'COMPLETED': return T.success;
      case 'FAILED_ESCALATED': return T.destructive;
      case 'REJECTED': return T.chart3;
      case 'CANCELLED': return T.muted;
      default: return T.muted;
    }
  }

  // Stack order for the terminal-status "executions over time" chart. Ordered so
  // COMPLETED (green) and FAILED_ESCALATED (red) are never adjacent — validated
  // CVD-safe; the only sub-threshold pair (green↔orange, dark) is relieved by the
  // 2px card-background spacer between segments plus the legend and tooltips.
  var TERMINAL_ORDER = ['COMPLETED', 'REJECTED', 'FAILED_ESCALATED', 'CANCELLED'];
  var FLOW_PALETTE = ['chart1', 'chart2', 'chart3', 'chart4', 'chart5'];

  function fmtSeconds(s) {
    if (s === null || s === undefined) return '—';
    s = Math.round(s);
    if (s < 60) return s + 's';
    if (s < 3600) return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
    return Math.floor(s / 3600) + 'h ' + Math.floor((s % 3600) / 60) + 'm';
  }

  // ---------- transforms (from the DashboardStats payload shape) ----------
  function dailyAxis(perDay, days) {
    var present = {};
    perDay.forEach(function (r) { present[r.day] = true; });
    var labels = [];
    var now = new Date();
    for (var i = days - 1; i >= 0; i--) {
      var d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - i));
      labels.push(d.toISOString().slice(0, 10));
    }
    Object.keys(present).forEach(function (day) {
      if (labels.indexOf(day) === -1) labels.push(day);
    });
    labels.sort();
    return labels;
  }

  function execOverTime(data) {
    var labels = dailyAxis(data.executionsPerDay, data.days);
    var byDayStatus = {};
    data.executionsPerDay.forEach(function (r) {
      byDayStatus[r.day + '|' + r.status] = r.count;
    });
    var datasets = TERMINAL_ORDER.map(function (status) {
      var series = labels.map(function (day) { return byDayStatus[day + '|' + status] || 0; });
      return { status: status, series: series };
    }).filter(function (d) {
      return d.series.some(function (v) { return v > 0; });
    }).map(function (d) {
      return {
        label: d.status,
        data: d.series,
        backgroundColor: statusColor(d.status),
        borderColor: T.card,
        borderWidth: 2,
        borderRadius: 4
      };
    });
    return { labels: labels, datasets: datasets };
  }

  function statusMix(data) {
    var rows = data.executionsByStatus.filter(function (r) { return r.count > 0; });
    return {
      labels: rows.map(function (r) { return r.status; }),
      data: rows.map(function (r) { return r.count; }),
      colors: rows.map(function (r) { return statusColor(r.status); })
    };
  }

  function throughputByFlow(data) {
    var totals = {};
    data.executionsByFlowAndStatus.forEach(function (r) {
      totals[r.flowId] = (totals[r.flowId] || 0) + r.count;
    });
    var flows = Object.keys(totals).sort();
    var labels = [];
    var values = [];
    var colors = [];
    var other = 0;
    flows.forEach(function (flow, i) {
      if (i < FLOW_PALETTE.length) {
        labels.push(flow);
        values.push(totals[flow]);
        colors.push(T[FLOW_PALETTE[i]]);
      } else {
        other += totals[flow];
      }
    });
    if (other > 0) {
      labels.push('Other');
      values.push(other);
      colors.push(T.muted);
    }
    return { labels: labels, values: values, colors: colors };
  }

  function stepFailures(data) {
    var rows = data.stepFailures;
    return {
      labels: rows.map(function (r) { return r.flowId + ' · ' + r.stepId; }),
      failures: rows.map(function (r) { return r.failures; }),
      retries: rows.map(function (r) { return r.retriesScheduled; })
    };
  }

  function connectorActions(data) {
    var names = [];
    var executed = {};
    var failed = {};
    var skipped = {};
    data.connectorOutcomes.forEach(function (r) {
      var name = r.connector || '(unknown)';
      if (names.indexOf(name) === -1) names.push(name);
      if (r.eventType === 'CONNECTOR_ACTION') executed[name] = (executed[name] || 0) + r.count;
      else if (r.eventType === 'CONNECTOR_FAILED') failed[name] = (failed[name] || 0) + r.count;
      else if (r.eventType === 'CONNECTOR_SKIPPED') skipped[name] = (skipped[name] || 0) + r.count;
    });
    names.sort();
    return {
      labels: names,
      executed: names.map(function (n) { return executed[n] || 0; }),
      failed: names.map(function (n) { return failed[n] || 0; }),
      skipped: names.map(function (n) { return skipped[n] || 0; })
    };
  }

  // ---------- shared Chart.js option builders ----------
  function axisScale(extra) {
    var base = {
      ticks: { color: T.muted },
      grid: { color: T.border },
      border: { display: false }
    };
    if (extra) Object.keys(extra).forEach(function (k) {
      if (k === 'ticks') {
        Object.keys(extra.ticks).forEach(function (tk) { base.ticks[tk] = extra.ticks[tk]; });
      } else {
        base[k] = extra[k];
      }
    });
    return base;
  }

  function legend(show) {
    return {
      display: show,
      position: 'bottom',
      labels: { color: T.muted, boxWidth: 12, boxHeight: 12 }
    };
  }

  // ---------- render (create-or-update) + empty-state handling ----------
  var charts = {};
  var lastData = null;

  function setEmpty(id, isEmpty) {
    var card = document.querySelector('[data-chart="' + id + '"]');
    if (!card) return;
    var region = card.querySelector('.chart-region');
    var empty = card.querySelector('[data-empty]');
    if (region) region.hidden = isEmpty;
    if (empty) empty.hidden = !isEmpty;
  }

  function upsert(id, type, chartData, options) {
    var existing = charts[id];
    if (!existing) {
      var el = document.getElementById(id);
      if (!el) return;
      charts[id] = new Chart(el.getContext('2d'), { type: type, data: chartData, options: options });
    } else {
      existing.data = chartData;
      existing.options = options;
      existing.resize();
      existing.update('none');
    }
  }

  function renderExecOverTime(data) {
    var t = execOverTime(data);
    var empty = t.datasets.length === 0;
    setEmpty('chart-executions', empty);
    if (empty) return;
    upsert('chart-executions', 'bar', { labels: t.labels, datasets: t.datasets }, {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        x: axisScale({ stacked: true }),
        y: axisScale({ stacked: true, beginAtZero: true, ticks: { precision: 0 } })
      },
      plugins: { legend: legend(true), tooltip: {} }
    });
  }

  function renderStatusMix(data) {
    var t = statusMix(data);
    var empty = t.data.length === 0;
    setEmpty('chart-status-mix', empty);
    if (empty) return;
    upsert('chart-status-mix', 'doughnut', {
      labels: t.labels,
      datasets: [{ data: t.data, backgroundColor: t.colors, borderColor: T.card, borderWidth: 2 }]
    }, {
      responsive: true,
      maintainAspectRatio: false,
      cutout: '65%',
      plugins: { legend: legend(true), tooltip: {} }
    });
  }

  function renderThroughput(data) {
    var t = throughputByFlow(data);
    var empty = t.values.length === 0;
    setEmpty('chart-throughput', empty);
    if (empty) return;
    upsert('chart-throughput', 'bar', {
      labels: t.labels,
      datasets: [{
        label: 'Executions',
        data: t.values,
        backgroundColor: t.colors,
        borderColor: T.card,
        borderWidth: 2,
        borderRadius: 4
      }]
    }, {
      indexAxis: 'y',
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        x: axisScale({ beginAtZero: true, ticks: { precision: 0 } }),
        y: axisScale()
      },
      plugins: { legend: legend(false), tooltip: {} }
    });
  }

  function renderStepFailures(data) {
    var t = stepFailures(data);
    var empty = t.labels.length === 0;
    setEmpty('chart-step-failures', empty);
    if (empty) return;
    upsert('chart-step-failures', 'bar', {
      labels: t.labels,
      datasets: [
        {
          label: 'Failures', data: t.failures, backgroundColor: T.destructive,
          borderColor: T.card, borderWidth: 2, borderRadius: 4
        },
        {
          label: 'Retries', data: t.retries, backgroundColor: T.warning,
          borderColor: T.card, borderWidth: 2, borderRadius: 4
        }
      ]
    }, {
      indexAxis: 'y',
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        x: axisScale({ beginAtZero: true, ticks: { precision: 0 } }),
        y: axisScale()
      },
      plugins: { legend: legend(true), tooltip: {} }
    });
  }

  function renderConnectors(data) {
    var t = connectorActions(data);
    var empty = t.labels.length === 0;
    setEmpty('chart-connectors', empty);
    if (empty) return;
    upsert('chart-connectors', 'bar', {
      labels: t.labels,
      datasets: [
        {
          label: 'Executed', data: t.executed, backgroundColor: T.chart2,
          borderColor: T.card, borderWidth: 2, borderRadius: 4
        },
        {
          label: 'Failed', data: t.failed, backgroundColor: T.destructive,
          borderColor: T.card, borderWidth: 2, borderRadius: 4
        },
        {
          label: 'Skipped', data: t.skipped, backgroundColor: T.muted,
          borderColor: T.card, borderWidth: 2, borderRadius: 4
        }
      ]
    }, {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        x: axisScale({ stacked: true }),
        y: axisScale({ stacked: true, beginAtZero: true, ticks: { precision: 0 } })
      },
      plugins: { legend: legend(true), tooltip: {} }
    });
  }

  function renderHitl(data) {
    var h = data.hitl || {};
    function set(id, value) {
      var el = document.getElementById(id);
      if (el) el.textContent = value;
    }
    set('hitl-pending', h.pending == null ? '—' : String(h.pending));
    set('hitl-avg', fmtSeconds(h.avgDecisionSeconds));
    set('hitl-oldest', fmtSeconds(h.oldestPendingAgeSeconds));
    set('hitl-decided', h.decidedInWindow == null ? '—' : String(h.decidedInWindow));
  }

  // ---------- refresh ----------
  function refresh(data) {
    lastData = data;
    readTokens();
    renderExecOverTime(data);
    renderStatusMix(data);
    renderThroughput(data);
    renderStepFailures(data);
    renderConnectors(data);
    renderHitl(data);
  }

  // ---------- initDashboardCharts ----------
  function initDashboardCharts() {
    readTokens();
    var days = parseInt(grid.getAttribute('data-days'), 10) || 14;
    var url = '/api/dashboard/stats?days=' + days;

    fetch(url, { headers: { Accept: 'application/json' } })
      .then(function (r) { if (!r.ok) throw new Error('status ' + r.status); return r.json(); })
      .then(function (data) { refresh(data); })
      .catch(function () { /* leave empty states; poller retries */ });

    if (typeof window.startPolling === 'function') {
      window.startPolling(url, 30000, refresh);
    }
  }

  // ---------- themechange ----------
  window.addEventListener('themechange', function () {
    if (lastData) refresh(lastData);
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initDashboardCharts);
  } else {
    initDashboardCharts();
  }
})();
