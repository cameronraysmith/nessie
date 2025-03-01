/*
 * Copyright (C) 2022 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.server;

import java.net.URI;
import java.util.Objects;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.projectnessie.client.ext.NessieClientResolver;

public class QuarkusNessieClientResolver extends NessieClientResolver {

  private static Integer getQuarkusTestPort() {
    return Objects.requireNonNull(
        Integer.getInteger("quarkus.http.test-port"),
        "System property not set correctly: quarkus.http.test-port");
  }

  @Override
  protected URI getBaseUri(ExtensionContext extensionContext) {
    return getQuarkusBaseTestUri();
  }

  public static URI getQuarkusBaseTestUri() {
    return URI.create(String.format("http://localhost:%d/", getQuarkusTestPort()));
  }
}
