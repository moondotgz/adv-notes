// JNI entry points for com.advnotes.app.tools.calculator.CasioEngine
#include <jni.h>
#include "casio_api.h"
#include <cstdint>
#include <string>
#include <vector>

namespace {
std::string str(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// The engine emits standard UTF-8 (incl. 4-byte sequences); build Java strings from UTF-16.
jstring jstr(JNIEnv *env, const std::string &s) {
    std::u16string u;
    for (size_t i = 0; i < s.size();) {
        unsigned char c = s[i];
        uint32_t cp; int l;
        if (c < 0x80) { cp = c; l = 1; } else if ((c >> 5) == 6) { cp = c & 0x1F; l = 2; }
        else if ((c >> 4) == 14) { cp = c & 0x0F; l = 3; } else { cp = c & 0x07; l = 4; }
        for (int k = 1; k < l && i + k < s.size(); k++) cp = (cp << 6) | (s[i + k] & 0x3F);
        i += l;
        if (cp >= 0x10000) { cp -= 0x10000; u += char16_t(0xD800 + (cp >> 10)); u += char16_t(0xDC00 + (cp & 0x3FF)); }
        else u += char16_t(cp);
    }
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), jsize(u.size()));
}

casio::Options opts(JNIEnv *env, jintArray a) {
    casio::Options o;
    jint v[8] = {0};
    jsize n = env->GetArrayLength(a);
    env->GetIntArrayRegion(a, 0, n < 8 ? n : 8, v);
    o.angle = v[0]; o.format = v[1]; o.digits = v[2]; o.exact = v[3] != 0;
    o.mixed = v[4] != 0; o.polar = v[5] != 0; o.complex = v[6] != 0; o.base = v[7];
    return o;
}

std::vector<double> doubles(JNIEnv *env, jdoubleArray a) {
    std::vector<double> v(size_t(env->GetArrayLength(a)));
    env->GetDoubleArrayRegion(a, 0, jsize(v.size()), v.data());
    return v;
}
} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_advnotes_app_tools_calculator_CasioEngine_nEvaluate(JNIEnv *env, jobject, jstring expr, jintArray o) {
    return jstr(env, casio::evaluate(str(env, expr), opts(env, o)));
}

JNIEXPORT jstring JNICALL
Java_com_advnotes_app_tools_calculator_CasioEngine_nPolynomial(JNIEnv *env, jobject, jdoubleArray c, jintArray o) {
    return jstr(env, casio::polynomial(doubles(env, c), opts(env, o)));
}

JNIEXPORT jstring JNICALL
Java_com_advnotes_app_tools_calculator_CasioEngine_nLinear(JNIEnv *env, jobject, jint n, jdoubleArray a, jdoubleArray b, jintArray o) {
    return jstr(env, casio::linearSystem(n, doubles(env, a), doubles(env, b), opts(env, o)));
}

JNIEXPORT jstring JNICALL
Java_com_advnotes_app_tools_calculator_CasioEngine_nStatistics(JNIEnv *env, jobject, jint t, jdoubleArray x, jdoubleArray y, jintArray o) {
    return jstr(env, casio::statistics(t, doubles(env, x), doubles(env, y), opts(env, o)));
}

JNIEXPORT jstring JNICALL
Java_com_advnotes_app_tools_calculator_CasioEngine_nVariables(JNIEnv *env, jobject, jintArray o) {
    return jstr(env, casio::variables(opts(env, o)));
}

JNIEXPORT void JNICALL
Java_com_advnotes_app_tools_calculator_CasioEngine_nSetVariable(JNIEnv *env, jobject, jstring name, jdouble re, jdouble im) {
    casio::setVariable(str(env, name), re, im);
}

JNIEXPORT void JNICALL
Java_com_advnotes_app_tools_calculator_CasioEngine_nReset(JNIEnv *, jobject) { casio::reset(); }

JNIEXPORT jstring JNICALL
Java_com_advnotes_app_tools_calculator_CasioEngine_nConstants(JNIEnv *env, jobject) { return jstr(env, casio::constants()); }

JNIEXPORT jstring JNICALL
Java_com_advnotes_app_tools_calculator_CasioEngine_nConversions(JNIEnv *env, jobject) { return jstr(env, casio::conversions()); }

} // extern "C"
