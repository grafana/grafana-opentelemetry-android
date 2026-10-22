#include <jni.h>
#include <stdlib.h>

extern "C" JNIEXPORT void JNICALL
Java_com_grafana_sessiondraftprobe_NativeCrash_abortNow(JNIEnv*, jclass) {
    abort();
}
