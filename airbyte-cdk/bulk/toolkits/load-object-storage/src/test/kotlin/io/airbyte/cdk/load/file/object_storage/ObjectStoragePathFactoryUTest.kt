/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.load.file.object_storage

import io.airbyte.cdk.load.command.DestinationStream
import io.airbyte.cdk.load.command.object_storage.ObjectStoragePathConfiguration
import io.airbyte.cdk.load.command.object_storage.ObjectStoragePathConfigurationProvider
import io.airbyte.cdk.load.file.TimeProvider
import io.airbyte.cdk.load.file.object_storage.ObjectStoragePathFactory.Companion.DEFAULT_FILE_FORMAT
import io.airbyte.cdk.load.file.object_storage.ObjectStoragePathFactory.Companion.DEFAULT_PATH_FORMAT
import io.airbyte.cdk.load.file.object_storage.ObjectStoragePathFactory.Companion.DEFAULT_STAGING_PREFIX_SUFFIX
import io.airbyte.cdk.load.state.object_storage.ObjectStorageDestinationState.Companion.OPTIONAL_ORDINAL_SUFFIX_PATTERN
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ObjectStoragePathFactoryUTest {
    @MockK lateinit var stream: DestinationStream
    @MockK lateinit var pathConfigProvider: ObjectStoragePathConfigurationProvider
    @MockK lateinit var timeProvider: TimeProvider

    @BeforeEach
    fun setup() {
        every { stream.descriptor } returns DestinationStream.Descriptor("test", "stream")
        every { timeProvider.syncTimeMillis() } returns 0
        every { timeProvider.currentTimeMillis() } returns 1
    }

    @Test
    fun `test matcher with suffix`() {
        every { pathConfigProvider.objectStoragePathConfiguration } returns
            ObjectStoragePathConfiguration(
                "prefix",
                null,
                "path/",
                "ambiguous_filename",
                false,
            )
        val factory = ObjectStoragePathFactory(pathConfigProvider, null, null, timeProvider)

        val matcher = factory.getPathMatcher(stream, "(-\\d+)?")
        val match1 = matcher.match("prefix/path/ambiguous_filename")
        assertNotNull(match1)
        assertNull(match1?.customSuffix)
        val match2 = matcher.match("prefix/path/ambiguous_filename-1")
        assertNotNull(match2)
        assertEquals(match2?.customSuffix, "-1")
    }

    @Test
    fun `test file pattern with variable in prefix`() {
        every { pathConfigProvider.objectStoragePathConfiguration } returns
            ObjectStoragePathConfiguration(
                "prefix-\${NAMESPACE}",
                "staging-\${NAMESPACE}",
                "\${STREAM_NAME}/",
                "any_filename",
                true,
            )
        val factory = ObjectStoragePathFactory(pathConfigProvider, null, null, timeProvider)
        assertEquals(
            "prefix-test/stream/any_filename",
            factory.getPathToFile(stream, 0L, isStaging = false)
        )
        assertEquals(
            "staging-test/stream/any_filename",
            factory.getPathToFile(stream, 0L, isStaging = true)
        )
    }

    @Test
    fun `test pattern matcher with variable in prefix`() {
        every { pathConfigProvider.objectStoragePathConfiguration } returns
            ObjectStoragePathConfiguration(
                "prefix-\${NAMESPACE}",
                "staging-\${NAMESPACE}",
                "\${STREAM_NAME}/",
                "any_filename",
                true,
            )
        val factory = ObjectStoragePathFactory(pathConfigProvider, null, null, timeProvider)
        val matcher = factory.getPathMatcher(stream, "(-foo)?")
        assertNotNull(matcher.match("prefix-test/stream/any_filename"))
        assertNotNull(matcher.match("prefix-test/stream/any_filename-foo"))
    }

    @Test
    fun `test pattern from null namespace`() {
        every { pathConfigProvider.objectStoragePathConfiguration } returns
            ObjectStoragePathConfiguration(
                "prefix",
                "staging",
                "\${NAMESPACE}/\${STREAM_NAME}/",
                "any_filename",
                true,
            )
        val streamWithNullNamespace = mockk<DestinationStream>()
        every { streamWithNullNamespace.descriptor } returns
            DestinationStream.Descriptor(null, "stream")
        val factory = ObjectStoragePathFactory(pathConfigProvider, null, null, timeProvider)
        assertEquals(
            "prefix/stream/any_filename",
            factory.getPathToFile(streamWithNullNamespace, 0L, isStaging = false)
        )
        assertEquals(
            "staging/stream/any_filename",
            factory.getPathToFile(streamWithNullNamespace, 0L, isStaging = true)
        )

        val matcher = factory.getPathMatcher(streamWithNullNamespace, "(-foo)?")
        assertNotNull(matcher.match("prefix/stream/any_filename"))
    }

    @ParameterizedTest
    @MethodSource("pathTemplateMatrix")
    fun `handles duplicate vars in path templates`(
        namespace: String?,
        name: String,
        bucketPathTemplate: String,
        filePathTemplate: String,
        fileNameTemplate: String,
        remoteFileKey: String,
        expectedPartNumber: Long,
    ) {
        every { pathConfigProvider.objectStoragePathConfiguration } returns
            ObjectStoragePathConfiguration(
                bucketPathTemplate,
                null,
                filePathTemplate,
                fileNameTemplate,
                false,
            )
        val stream = mockk<DestinationStream>()
        every { stream.descriptor } returns DestinationStream.Descriptor(namespace, name)
        val factory = ObjectStoragePathFactory(pathConfigProvider, null, null, timeProvider)

        val matcher = factory.getPathMatcher(stream, OPTIONAL_ORDINAL_SUFFIX_PATTERN)

        val result = matcher.match(remoteFileKey)

        assertNotNull(result)
        assertEquals(expectedPartNumber, result!!.partNumber)
    }

    companion object {
        @JvmStatic
        private fun pathTemplateMatrix(): Stream<Arguments> {
            return Stream.of(
                Arguments.arguments(
                    "namespace1",
                    "stream_abc",
                    "\${NAMESPACE}/\${STREAM_NAME}",
                    "\${YEAR}/\${MONTH}/\${DAY}/\${NAMESPACE}_\${BIRD_DOG}_\${STREAM_NAME}_\${YEAR}_\${MONTH}_\${DAY}_\${EPOCH}_",
                    "{part_number}{format_extension}",
                    "namespace1/stream_abc/2024/08/30/namespace1_BIRD_DOG_stream_abc_2024_08_30_1736900845782_1",
                    1L,
                ),
                Arguments.arguments(
                    "namespace1",
                    "stream_abc",
                    "\${NAMESPACE}/\${STREAM_NAME}",
                    "\${YEAR}/\${MONTH}/\${DAY}/\${NAMESPACE}_\${STREAM_NAME}_\${YEAR}_\${MONTH}_\${DAY}_\${EPOCH}_",
                    "{part_number}{format_extension}",
                    "namespace1/stream_abc/2024/08/30/namespace1_stream_abc_2024_08_30_1736900845782_5",
                    5L,
                ),
                Arguments.arguments(
                    "namespace1",
                    "stream_abc",
                    "\${NAMESPACE}/\${STREAM_NAME}",
                    "\${YEAR}/\${MONTH}/\${DAY}/\${NAMESPACE}_\${STREAM_NAME}_\${YEAR}_\${MONTH}_\${DAY}_\${EPOCH}_",
                    "{part_number}{format_extension}",
                    "namespace1/stream_abc/2024/08/30/namespace1_stream_abc_2024_08_30_1736900845782_7",
                    7L,
                ),
                Arguments.arguments(
                    "namespace3",
                    "stream_456",
                    "\${NAMESPACE}/\${STREAM_NAME}/\${STREAM_NAME}/\${STREAM_NAME}/\${STREAM_NAME}",
                    "_",
                    "{part_number}{format_extension}",
                    "namespace3/stream_456/stream_456/stream_456/stream_456/_0",
                    0,
                ),
                Arguments.arguments(
                    "namespace2",
                    "stream_123",
                    "\${NAMESPACE}/\${STREAM_NAME}/\${STREAM_NAME}/\${STREAM_NAME}/\${STREAM_NAME}",
                    "",
                    "{part_number}{format_extension}",
                    "namespace2/stream_123/stream_123/stream_123/stream_1230",
                    0,
                ),
                Arguments.arguments(
                    "namespace1",
                    "stream_abc",
                    "\${NAMESPACE}/\${STREAM_NAME}/\${STREAM_NAME}/\${STREAM_NAME}/\${STREAM_NAME}",
                    "",
                    "{part_number}{format_extension}",
                    "namespace1/stream_abc/stream_abc/stream_abc/stream_abc0",
                    0,
                ),
                Arguments.arguments(
                    null,
                    "stream_abc",
                    "asdf",
                    "\${NAMESPACE}/\${STREAM_NAME}/\${STREAM_NAME}/\${STREAM_NAME}/\${STREAM_NAME}",
                    "{part_number}{format_extension}",
                    "asdf/stream_abc/stream_abc/stream_abc/stream_abc0",
                    0,
                ),
                Arguments.arguments(
                    null,
                    "stream_abc",
                    "",
                    "\${NAMESPACE}/\${STREAM_NAME}/\${STREAM_NAME}/\${STREAM_NAME}/\${STREAM_NAME}",
                    "{part_number}{format_extension}",
                    "/stream_abc/stream_abc/stream_abc/stream_abc6",
                    6,
                ),
                Arguments.arguments(
                    "namespace_2",
                    "stream_abcd",
                    "",
                    DEFAULT_PATH_FORMAT,
                    DEFAULT_FILE_FORMAT,
                    "namespace_2/stream_abcd/2024_08_30_1736900845782_7",
                    7,
                ),
                Arguments.arguments(
                    "namespace_2",
                    "stream_abcd",
                    DEFAULT_STAGING_PREFIX_SUFFIX,
                    DEFAULT_PATH_FORMAT,
                    DEFAULT_FILE_FORMAT,
                    "__airbyte_tmp/namespace_2/stream_abcd/2024_08_30_1736900845782_7",
                    7,
                ),
            )
        }
    }
}
