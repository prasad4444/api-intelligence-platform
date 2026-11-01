package com.apiintel;

import static org.junit.jupiter.api.Assertions.*;

import com.apiintel.tests.SchemaExamples;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;

class SchemaExamplesTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void schemaBoundariesAndRefs() throws Exception {
    var doc = json.readTree(
      "{\"components\":{\"schemas\":{\"Name\":{\"type\":\"string\",\"maxLength\":12}}}}"
    );
    var schema = json.readTree(
      "{\"type\":\"object\",\"properties\":{\"name\":{\"$ref\":\"#/components/schemas/Name\"},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":99},\"amount\":{\"type\":\"number\",\"maximum\":2.5},\"ok\":{\"type\":\"boolean\"},\"items\":{\"type\":\"array\",\"maxItems\":2,\"items\":{\"enum\":[\"a\",\"b\"]}}}}"
    );
    var sample = SchemaExamples.sample(schema, doc, true, 0);
    assertEquals(12, sample.path("name").asText().length());
    assertEquals(99, sample.path("count").asInt());
    assertEquals(2, sample.path("items").size());
    assertEquals(2.5, sample.path("amount").asDouble());
    assertTrue(sample.path("ok").asBoolean());
    for (String format : java.util.List.of("date", "date-time", "email"))
      assertFalse(
        SchemaExamples.sample(
          json.readTree("{\"format\":\"" + format + "\"}"),
          doc,
          false,
          0
        )
          .asText()
          .isBlank()
      );
    assertEquals(
      "fixed",
      SchemaExamples.sample(
        json.readTree("{\"default\":\"fixed\"}"),
        doc,
        false,
        0
      ).asText()
    );
    assertEquals(
      "example",
      SchemaExamples.sample(
        json.readTree("{\"example\":\"example\"}"),
        doc,
        false,
        0
      ).asText()
    );
    assertTrue(SchemaExamples.sample(schema, doc, false, 9).isNull());
  }

  @Test
  void requestParametersAuthAndMalformedNegativeBody() throws Exception {
    var doc = json.readTree(
      "{\"security\":[{\"bearer\":[]}],\"components\":{\"securitySchemes\":{\"bearer\":{\"type\":\"http\",\"scheme\":\"bearer\"}}}}"
    );
    var operation = json.readTree(
      "{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"schema\":{\"type\":\"integer\"}},{\"name\":\"limit\",\"in\":\"query\",\"schema\":{\"type\":\"integer\"}}],\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{\"enabled\":{\"type\":\"boolean\"}}}}}}}"
    );
    var request = SchemaExamples.request(
      doc,
      "/items/{id}",
      "post",
      operation,
      json.createObjectNode(),
      "positive"
    );
    assertEquals("{{baseUrl}}/items/1?limit=1", request.path("url").asText());
    assertTrue(request.path("headers").has("Authorization"));
    assertFalse(
      SchemaExamples.request(
        doc,
        "/items/{id}",
        "post",
        operation,
        json.createObjectNode(),
        "security"
      )
        .path("headers")
        .has("Authorization")
    );
    assertEquals(
      "{invalid",
      SchemaExamples.request(
        doc,
        "/items/{id}",
        "post",
        operation,
        json.createObjectNode(),
        "negative"
      )
        .path("body")
        .asText()
    );
  }
}
