package com.personal.tpm.data

import org.junit.Assert.*
import org.junit.Test

class OnlineUpdateTest {
    @Test fun versionNamesAreComparedByNumericComponents() {
        assertTrue(compareUpdateVersions("2.1.19", "2.1.17.2") > 0)
        assertTrue(compareUpdateVersions("2.1.17.2", "2.1.17") > 0)
        assertTrue(compareUpdateVersions("2.1.19", "2.1.9") > 0)
        assertEquals(0, compareUpdateVersions("2.1.19", "2.1.19.0"))
    }

    @Test fun choosesLatestNamedApkAndUsesTrustedShareHost() {
        val rows = Jv.parse("""[
            {"name_all":"wataa-2.1.17.2.apk","id":"abc123"},
            {"name_all":"wataa-2.1.19.apk","id":"new123"},
            {"name_all":"wataa-2.1.9.apk","id":"old123"},
            {"name_all":"unrelated-9.9.9.apk","id":"unrelated"},
            {"name_all":"wataa-9.9.9.apk","id":"https://elsewhere.example/"},
            {"name_all":"wataa-8.8.8.apk","id":"-1"}
        ]""").array
        assertEquals(OnlineApk("2.1.19", "wataa-2.1.19.apk", "https://wataa.lanzoum.com/new123"), latestOnlineApk(rows))
    }

    @Test fun parsesThePublicFolderFormWithoutExecutingJavaScript() {
        val form = readUpdateFolderForm("""
            var randomTime = '123456';
            var randomKey = 'temporary';
            $.ajax({
                url: '/filemoreajax.php?file=42',
                data: {'lx':2,'fid':42,'uid':'7','puid':'public','pg':pgs,'rep':'0',
                    't':randomTime,'k':randomKey,'up':1,'vip':'1','webfoldersign':''},
            });
        """)
        assertEquals("/filemoreajax.php?file=42", form.path)
        assertEquals("123456", form.values["t"])
        assertEquals("temporary", form.values["k"])
        assertEquals("1", form.values["pg"])
    }
}
