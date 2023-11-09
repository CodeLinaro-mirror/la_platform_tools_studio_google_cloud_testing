/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.gct.testing

import com.google.common.truth.Truth.assertThat
import com.google.gct.testing.CloudTestingUtils.getTimestampAtMidnightInPT
import org.junit.Test
import java.time.Instant

class CloudTestingUtilsTest {
  @Test
  fun testTimestampAtMidnightInPT() {
    // Test the last midnight before Tue Oct 31 14:44:29 2023 in Pacific Daylight Time.
    assertThat(getTimestampAtMidnightInPT(Instant.ofEpochMilli(1698788669000L))).isEqualTo(1698735600000)
    // Test the last midnight before Thu Nov 30 13:44:29 2023 in Pacific Standard Time.
    assertThat(getTimestampAtMidnightInPT(Instant.ofEpochMilli(1701380669000L))).isEqualTo(1701331200000)
  }
}
