package com.apiintel.capacity;

import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CapacityService {

  private final RecordStore store;

  public CapacityService(RecordStore store) {
    this.store = store;
  }

  public List<Map<String, Object>> calculate(String sprintId) {
    var sprint = store.get("sprints", sprintId);
    LocalDate start = LocalDate.parse(sprint.path("start").asText()),
      end = LocalDate.parse(sprint.path("end").asText());
    if (
      end.isBefore(start) || end.isAfter(start.plusDays(366))
    ) throw new IllegalArgumentException("Sprint must span 1–367 days");
    var result = new ArrayList<Map<String, Object>>();
    for (var person : store.list("team")) {
      String name = person.path("name").asText(),
        role = person.path("role").asText("Dev"),
        region = person.path("region").asText("USA");
      double hours =
        (person.path("hoursPerDay").asDouble(8) *
          person.path("allocation").asDouble(100)) /
        100;
      var days = new ArrayList<Map<String, Object>>();
      double capacity = 0;
      for (var d = start; !d.isAfter(end); d = d.plusDays(1)) {
        final LocalDate date = d;
        boolean weekend = d.getDayOfWeek().getValue() > 5;
        boolean holiday = store
          .list("holidays")
          .stream()
          .anyMatch(
            h ->
              h.path("date").asText().equals(date.toString()) &&
              h.path("region").asText().equals(region)
          );
        boolean leave = store
          .list("leaves")
          .stream()
          .anyMatch(
            l ->
              l.path("name").asText().equals(name) &&
              !date.isBefore(LocalDate.parse(l.path("from").asText())) &&
              !date.isAfter(LocalDate.parse(l.path("to").asText()))
          );
        double available = weekend || holiday || leave ? 0 : hours;
        capacity += available;
        days.add(
          Map.of(
            "date",
            date.toString(),
            "hours",
            available,
            "reason",
            weekend
              ? "Weekend"
              : holiday
                ? "Holiday"
                : leave
                  ? "Leave"
                  : "Available"
          )
        );
      }
      String assignee = role.equals("Test") ? "tester" : "developer",
        points = role.equals("Test") ? "testPoints" : "devPoints";
      double committed = store
        .list("stories")
        .stream()
        .filter(
          s ->
            s.path(assignee).asText().equals(name) &&
            s.path("sprint").asText().equals(sprint.path("name").asText())
        )
        .mapToDouble(s -> s.path(points).asDouble())
        .sum();
      double pointHours = sprint.path("hoursPerPoint").asDouble(8);
      if (pointHours <= 0) throw new IllegalArgumentException(
        "Hours per point must be positive"
      );
      double pointCapacity = capacity / pointHours;
      result.add(
        Map.of(
          "name",
          name,
          "role",
          role,
          "region",
          region,
          "hours",
          capacity,
          "capacity",
          pointCapacity,
          "committed",
          committed,
          "utilization",
          pointCapacity == 0
            ? committed > 0
              ? 100
              : 0
            : Math.round((committed / pointCapacity) * 100),
          "days",
          days
        )
      );
    }
    return result;
  }
}
