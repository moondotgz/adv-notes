// Minimal Qt-compatibility layer so the Qt-free parts of moondotgz/casio (src/core) compile
// without Qt. Implements only the subset of QString / QChar / containers that the engine uses,
// with Qt's semantics (UTF-16 strings, QString::number formats, QMap/QSet/QHash APIs).
#pragma once

#include <algorithm>
#include <clocale>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <initializer_list>
#include <map>
#include <regex>
#include <set>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

// The shim promises C-locale numbers like Qt, but printf/strtod follow the process locale. Android's libc is
// always "C", yet a JVM on a host set to e.g. pt_BR switches to a decimal comma (this broke the host tests), so
// convert explicitly in both directions.
namespace qtshim_num {
inline std::string decimalPoint() { const char *d = std::localeconv()->decimal_point; return (d && *d) ? d : "."; }
// C-locale text ("1.5") -> what strtod expects right now. A locale-style separator ("1,5") isn't C syntax: reject it.
inline std::string toLocalForm(std::string s) {
    const std::string dp = decimalPoint();
    if (dp == ".") return s;
    if (s.find(dp) != std::string::npos) return "\x01";
    size_t at = s.find('.');
    if (at != std::string::npos) s.replace(at, 1, dp);
    return s;
}
// printf output -> C-locale text
inline std::string fromLocalForm(std::string s) {
    const std::string dp = decimalPoint();
    if (dp == ".") return s;
    size_t at = s.find(dp);
    if (at != std::string::npos) s.replace(at, dp.size(), ".");
    return s;
}
} // namespace qtshim_num

using qint8 = int8_t;
using quint8 = uint8_t;
using qint16 = int16_t;
using quint16 = uint16_t;
using qint32 = int32_t;
using quint32 = uint32_t;
using qint64 = int64_t;
using quint64 = uint64_t;
using qsizetype = std::ptrdiff_t;
using qreal = double;

#define Q_UNUSED(x) (void)(x)
#define QStringLiteral(s) QString::fromUtf8(s)
#define QLatin1String(s) QString::fromUtf8(s)
#define QLatin1Char(c) QChar(c)
#define qPrintable(s) ((s).toStdString().c_str())

template <typename T> constexpr const T &qMin(const T &a, const T &b) { return b < a ? b : a; }
template <typename T> constexpr const T &qMax(const T &a, const T &b) { return a < b ? b : a; }
template <typename T> constexpr T qAbs(const T &a) { return a < 0 ? -a : a; }
template <typename T> constexpr const T &qBound(const T &lo, const T &v, const T &hi) { return qMax(lo, qMin(v, hi)); }

// ---------------------------------------------------------------------------------------------
class QChar {
public:
    char16_t ucs = 0;
    constexpr QChar() = default;
    constexpr QChar(char16_t c) : ucs(c) {}
    constexpr QChar(char c) : ucs(char16_t(static_cast<unsigned char>(c))) {}
    constexpr QChar(int c) : ucs(char16_t(c)) {}
    constexpr QChar(unsigned c) : ucs(char16_t(c)) {}
    constexpr char16_t unicode() const { return ucs; }
    constexpr bool isNull() const { return ucs == 0; }
    bool isDigit() const { return ucs >= u'0' && ucs <= u'9'; }
    int digitValue() const { return isDigit() ? ucs - u'0' : -1; }
    bool isLetter() const {
        if ((ucs >= u'a' && ucs <= u'z') || (ucs >= u'A' && ucs <= u'Z')) return true;
        if (ucs < 0xC0) return false;
        // Greek, Latin-1/Extended letters and the like: treat non-symbol BMP letters as letters.
        if (ucs >= 0x00C0 && ucs <= 0x024F && ucs != 0xD7 && ucs != 0xF7) return true;
        if (ucs >= 0x0370 && ucs <= 0x03FF) return true;   // Greek
        if (ucs >= 0x0400 && ucs <= 0x04FF) return true;   // Cyrillic
        return false;
    }
    bool isLetterOrNumber() const { return isLetter() || isDigit(); }
    bool isSpace() const { return ucs == u' ' || ucs == u'\t' || ucs == u'\n' || ucs == u'\r' || ucs == 0xA0 || ucs == 0x2009 || ucs == 0x202F; }
    bool isUpper() const { return ucs >= u'A' && ucs <= u'Z'; }
    bool isLower() const { return ucs >= u'a' && ucs <= u'z'; }
    QChar toUpper() const { return isLower() ? QChar(char16_t(ucs - 32)) : *this; }
    QChar toLower() const { return isUpper() ? QChar(char16_t(ucs + 32)) : *this; }
    char toLatin1() const { return ucs < 256 ? char(ucs) : 0; }
    friend constexpr bool operator==(QChar a, QChar b) { return a.ucs == b.ucs; }
    friend constexpr bool operator!=(QChar a, QChar b) { return a.ucs != b.ucs; }
    friend constexpr bool operator<(QChar a, QChar b) { return a.ucs < b.ucs; }
    friend constexpr bool operator<=(QChar a, QChar b) { return a.ucs <= b.ucs; }
    friend constexpr bool operator>(QChar a, QChar b) { return a.ucs > b.ucs; }
    friend constexpr bool operator>=(QChar a, QChar b) { return a.ucs >= b.ucs; }
};

class QStringList;

// ---------------------------------------------------------------------------------------------
class QString {
public:
    std::u16string d;

    QString() = default;
    QString(const char *utf8) { if (utf8) d = fromUtf8(utf8).d; }
    QString(const char16_t *s) : d(s ? s : u"") {}
    QString(std::u16string s) : d(std::move(s)) {}
    QString(QChar c) : d(1, c.ucs) {}
    QString(qsizetype n, QChar c) : d(size_t(n), c.ucs) {}
    QString(int n, char c) : d(size_t(n), char16_t(c)) {}

    static QString fromUtf8(const char *s, qsizetype len = -1) {
        QString out;
        if (!s) return out;
        size_t n = len < 0 ? std::strlen(s) : size_t(len);
        for (size_t i = 0; i < n;) {
            unsigned char c = s[i];
            uint32_t cp; int l;
            if (c < 0x80) { cp = c; l = 1; }
            else if ((c >> 5) == 6) { cp = c & 0x1F; l = 2; }
            else if ((c >> 4) == 14) { cp = c & 0x0F; l = 3; }
            else { cp = c & 0x07; l = 4; }
            for (int k = 1; k < l && i + k < n; k++) cp = (cp << 6) | (s[i + k] & 0x3F);
            i += l;
            if (cp >= 0x10000) { cp -= 0x10000; out.d += char16_t(0xD800 + (cp >> 10)); out.d += char16_t(0xDC00 + (cp & 0x3FF)); }
            else out.d += char16_t(cp);
        }
        return out;
    }
    static QString fromLatin1(const char *s, qsizetype len = -1) {
        QString out; if (!s) return out;
        size_t n = len < 0 ? std::strlen(s) : size_t(len);
        for (size_t i = 0; i < n; i++) out.d += char16_t(static_cast<unsigned char>(s[i]));
        return out;
    }
    static QString fromStdString(const std::string &s) { return fromUtf8(s.c_str(), qsizetype(s.size())); }
    std::string toStdString() const {
        std::string out;
        for (size_t i = 0; i < d.size(); i++) {
            uint32_t cp = d[i];
            if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < d.size()) { cp = 0x10000 + ((cp - 0xD800) << 10) + (d[i + 1] - 0xDC00); i++; }
            if (cp < 0x80) out += char(cp);
            else if (cp < 0x800) { out += char(0xC0 | (cp >> 6)); out += char(0x80 | (cp & 0x3F)); }
            else if (cp < 0x10000) { out += char(0xE0 | (cp >> 12)); out += char(0x80 | ((cp >> 6) & 0x3F)); out += char(0x80 | (cp & 0x3F)); }
            else { out += char(0xF0 | (cp >> 18)); out += char(0x80 | ((cp >> 12) & 0x3F)); out += char(0x80 | ((cp >> 6) & 0x3F)); out += char(0x80 | (cp & 0x3F)); }
        }
        return out;
    }
    std::string toUtf8() const { return toStdString(); }

    qsizetype size() const { return qsizetype(d.size()); }
    qsizetype length() const { return size(); }
    qsizetype count() const { return size(); }
    bool isEmpty() const { return d.empty(); }
    bool isNull() const { return d.empty(); }
    void clear() { d.clear(); }
    void reserve(qsizetype n) { d.reserve(size_t(n)); }
    QChar at(qsizetype i) const { return QChar(d.at(size_t(i))); }
    QChar operator[](qsizetype i) const { return QChar(d[size_t(i)]); }
    QChar front() const { return QChar(d.front()); }
    QChar back() const { return QChar(d.back()); }
    QChar first() const { return front(); }
    QChar last() const { return back(); }
    const char16_t *utf16() const { return d.c_str(); }

    struct const_iterator {
        std::u16string::const_iterator it;
        QChar operator*() const { return QChar(*it); }
        const_iterator &operator++() { ++it; return *this; }
        bool operator!=(const const_iterator &o) const { return it != o.it; }
        bool operator==(const const_iterator &o) const { return it == o.it; }
    };
    const_iterator begin() const { return {d.begin()}; }
    const_iterator end() const { return {d.end()}; }
    const_iterator cbegin() const { return begin(); }
    const_iterator cend() const { return end(); }

    QString mid(qsizetype pos, qsizetype n = -1) const {
        if (pos < 0) { if (n >= 0) n += pos; pos = 0; }
        if (pos >= size()) return QString();
        if (n < 0 || pos + n > size()) n = size() - pos;
        return QString(d.substr(size_t(pos), size_t(n)));
    }
    QString left(qsizetype n) const { return n < 0 ? *this : QString(d.substr(0, size_t(std::min(n, size())))); }
    QString right(qsizetype n) const { return n >= size() || n < 0 ? *this : QString(d.substr(size_t(size() - n))); }
    QString chopped(qsizetype n) const { return left(size() - n); }
    void chop(qsizetype n) { d.resize(size_t(std::max<qsizetype>(0, size() - n))); }
    void truncate(qsizetype n) { if (n < size()) d.resize(size_t(std::max<qsizetype>(0, n))); }

    bool startsWith(const QString &s) const { return d.compare(0, s.d.size(), s.d) == 0 && d.size() >= s.d.size(); }
    bool startsWith(QChar c) const { return !d.empty() && d.front() == c.ucs; }
    bool startsWith(const char *s) const { return startsWith(QString(s)); }
    bool endsWith(const QString &s) const { return d.size() >= s.d.size() && d.compare(d.size() - s.d.size(), s.d.size(), s.d) == 0; }
    bool endsWith(QChar c) const { return !d.empty() && d.back() == c.ucs; }
    bool endsWith(const char *s) const { return endsWith(QString(s)); }
    bool contains(const QString &s) const { return d.find(s.d) != std::u16string::npos; }
    bool contains(QChar c) const { return d.find(c.ucs) != std::u16string::npos; }
    bool contains(const char *s) const { return contains(QString(s)); }
    qsizetype indexOf(const QString &s, qsizetype from = 0) const { auto p = d.find(s.d, size_t(std::max<qsizetype>(0, from))); return p == std::u16string::npos ? -1 : qsizetype(p); }
    qsizetype indexOf(QChar c, qsizetype from = 0) const { auto p = d.find(c.ucs, size_t(std::max<qsizetype>(0, from))); return p == std::u16string::npos ? -1 : qsizetype(p); }
    qsizetype indexOf(const char *s, qsizetype from = 0) const { return indexOf(QString(s), from); }
    qsizetype lastIndexOf(const QString &s) const { auto p = d.rfind(s.d); return p == std::u16string::npos ? -1 : qsizetype(p); }
    qsizetype lastIndexOf(QChar c) const { auto p = d.rfind(c.ucs); return p == std::u16string::npos ? -1 : qsizetype(p); }
    qsizetype count(QChar c) const { return qsizetype(std::count(d.begin(), d.end(), c.ucs)); }

    QString &append(const QString &s) { d += s.d; return *this; }
    QString &append(QChar c) { d += c.ucs; return *this; }
    QString &prepend(const QString &s) { d = s.d + d; return *this; }
    QString &prepend(QChar c) { d.insert(d.begin(), c.ucs); return *this; }
    QString &insert(qsizetype pos, const QString &s) { d.insert(size_t(std::min(pos, size())), s.d); return *this; }
    QString &insert(qsizetype pos, QChar c) { return insert(pos, QString(c)); }
    void push_back(QChar c) { d += c.ucs; }
    void push_back(const QString &s) { d += s.d; }

    QString &remove(qsizetype pos, qsizetype n) { if (pos < size()) d.erase(size_t(pos), size_t(std::min(n, size() - pos))); return *this; }
    QString &remove(QChar c) { d.erase(std::remove(d.begin(), d.end(), c.ucs), d.end()); return *this; }
    QString &remove(const QString &s) { if (!s.isEmpty()) { size_t p; while ((p = d.find(s.d)) != std::u16string::npos) d.erase(p, s.d.size()); } return *this; }
    QString &replace(const QString &a, const QString &b) {
        if (a.isEmpty()) return *this;
        size_t p = 0;
        while ((p = d.find(a.d, p)) != std::u16string::npos) { d.replace(p, a.d.size(), b.d); p += b.d.size(); }
        return *this;
    }
    QString &replace(QChar a, QChar b) { std::replace(d.begin(), d.end(), a.ucs, b.ucs); return *this; }
    QString &replace(QChar a, const QString &b) { return replace(QString(a), b); }
    QString &replace(const char *a, const char *b) { return replace(QString(a), QString(b)); }
    QString &replace(qsizetype pos, qsizetype n, const QString &b) { d.replace(size_t(pos), size_t(n), b.d); return *this; }

    QString toUpper() const { QString r = *this; for (auto &c : r.d) c = QChar(c).toUpper().ucs; return r; }
    QString toLower() const { QString r = *this; for (auto &c : r.d) c = QChar(c).toLower().ucs; return r; }
    QString trimmed() const {
        size_t a = 0, b = d.size();
        while (a < b && QChar(d[a]).isSpace()) a++;
        while (b > a && QChar(d[b - 1]).isSpace()) b--;
        return QString(d.substr(a, b - a));
    }
    QString simplified() const {
        QString out; bool sp = false;
        for (char16_t c : trimmed().d) { if (QChar(c).isSpace()) { if (!sp) out.d += u' '; sp = true; } else { out.d += c; sp = false; } }
        return out;
    }
    enum NormalizationForm { NormalizationForm_D, NormalizationForm_C, NormalizationForm_KD, NormalizationForm_KC };
    /**
     * Canonical decomposition for the precomposed letters the calculator uses (statistics
     * variables like ŷ, ȳ, x̂ are spelled with combining marks). Other forms return a copy.
     */
    QString normalized(NormalizationForm f) const {
        if (f != NormalizationForm_D && f != NormalizationForm_KD) return *this;
        static const std::pair<char16_t, std::u16string> table[] = {
            {0x00E2, u"a\u0302"}, {0x00EA, u"e\u0302"}, {0x00EE, u"i\u0302"}, {0x00F4, u"o\u0302"}, {0x00FB, u"u\u0302"},
            {0x0177, u"y\u0302"}, {0x0176, u"Y\u0302"}, {0x0175, u"w\u0302"}, {0x00C2, u"A\u0302"},
            {0x0101, u"a\u0304"}, {0x0113, u"e\u0304"}, {0x012B, u"i\u0304"}, {0x014D, u"o\u0304"}, {0x016B, u"u\u0304"},
            {0x0233, u"y\u0304"}, {0x0232, u"Y\u0304"}, {0x0100, u"A\u0304"},
        };
        QString out;
        for (char16_t c : d) {
            const std::u16string *rep = nullptr;
            for (auto &e : table) if (e.first == c) { rep = &e.second; break; }
            if (rep) out.d += *rep; else out.d += c;
        }
        return out;
    }

    QString repeated(qsizetype n) const { QString r; for (qsizetype i = 0; i < n; i++) r.d += d; return r; }

    QStringList split(const QString &sep) const;
    QStringList split(QChar sep) const;

    // Number parsing (C locale, like Qt)
    double toDouble(bool *ok = nullptr) const {
        std::string s = qtshim_num::toLocalForm(trimmed().toStdString());
        char *end = nullptr;
        double v = s.empty() ? 0 : std::strtod(s.c_str(), &end);
        bool good = !s.empty() && end && *end == '\0';
        if (ok) *ok = good;
        return good ? v : 0.0;
    }
    long long toLongLong(bool *ok = nullptr, int base = 10) const {
        std::string s = trimmed().toStdString();
        char *end = nullptr;
        long long v = s.empty() ? 0 : std::strtoll(s.c_str(), &end, base);
        bool good = !s.empty() && end && *end == '\0';
        if (ok) *ok = good;
        return good ? v : 0;
    }
    unsigned long long toULongLong(bool *ok = nullptr, int base = 10) const {
        std::string s = trimmed().toStdString();
        char *end = nullptr;
        unsigned long long v = s.empty() ? 0 : std::strtoull(s.c_str(), &end, base);
        bool good = !s.empty() && end && *end == '\0' && s[0] != '-';
        if (ok) *ok = good;
        return good ? v : 0;
    }
    int toInt(bool *ok = nullptr, int base = 10) const {
        bool o = false; long long v = toLongLong(&o, base);
        if (o && (v < INT32_MIN || v > INT32_MAX)) o = false;
        if (ok) *ok = o;
        return o ? int(v) : 0;
    }
    unsigned toUInt(bool *ok = nullptr, int base = 10) const {
        bool o = false; unsigned long long v = toULongLong(&o, base);
        if (o && v > UINT32_MAX) o = false;
        if (ok) *ok = o;
        return o ? unsigned(v) : 0;
    }

    // QString::number, matching Qt's output formats
    static QString number(double x, char fmt = 'g', int prec = 6) {
        if (std::isnan(x)) return QString("nan");
        if (std::isinf(x)) return QString(x < 0 ? "-inf" : "inf");
        char spec[8];
        std::snprintf(spec, sizeof spec, "%%.%d%c", prec < 0 ? 6 : prec, fmt);
        char buf[512];
        std::snprintf(buf, sizeof buf, spec, x);
        return QString(qtshim_num::fromLocalForm(buf).c_str());
    }
    static QString number(float x, char fmt = 'g', int prec = 6) { return number(double(x), fmt, prec); }
    static QString number(long long v, int base = 10) {
        if (base == 10) return QString(std::to_string(v).c_str());
        bool neg = v < 0;
        unsigned long long u = neg ? 0ULL - (unsigned long long)v : (unsigned long long)v;
        return QString(neg ? "-" : "") + numberU(u, base);
    }
    static QString number(unsigned long long v, int base = 10) { return numberU(v, base); }
    static QString number(int v, int base = 10) { return number((long long)v, base); }
    static QString number(unsigned v, int base = 10) { return numberU(v, base); }
    static QString number(long v, int base = 10) { return number((long long)v, base); }
    static QString number(unsigned long v, int base = 10) { return numberU(v, base); }

    // arg(): replaces the lowest-numbered %n
    QString arg(const QString &a) const {
        int lowest = 100; size_t pos = 0;
        for (size_t i = 0; i + 1 < d.size(); i++)
            if (d[i] == u'%' && d[i + 1] >= u'1' && d[i + 1] <= u'9' && d[i + 1] - u'0' < lowest) { lowest = d[i + 1] - u'0'; pos = i; }
        if (lowest == 100) return *this;
        QString r; std::u16string tag = {u'%', char16_t(u'0' + lowest)};
        size_t p = 0, q;
        r.d = d;
        while ((q = r.d.find(tag, p)) != std::u16string::npos) { r.d.replace(q, 2, a.d); p = q + a.d.size(); }
        (void)pos;
        return r;
    }
    QString arg(QChar c) const { return arg(QString(c)); }
    QString arg(const char *s) const { return arg(QString(s)); }
    QString arg(double v, int width = 0, char fmt = 'g', int prec = -1) const { (void)width; return arg(number(v, fmt, prec < 0 ? 6 : prec)); }
    QString arg(int v) const { return arg(number(v)); }
    QString arg(long long v) const { return arg(number(v)); }
    QString arg(unsigned v) const { return arg(number(v)); }

    QString &operator+=(const QString &s) { d += s.d; return *this; }
    QString &operator+=(QChar c) { d += c.ucs; return *this; }
    QString &operator+=(const char *s) { d += QString(s).d; return *this; }
    QString &operator+=(char c) { d += char16_t(c); return *this; }
    QString &operator<<(const QString &s) { d += s.d; return *this; }

    friend QString operator+(const QString &a, const QString &b) { return QString(a.d + b.d); }
    friend QString operator+(const QString &a, const char *b) { return a + QString(b); }
    friend QString operator+(const char *a, const QString &b) { return QString(a) + b; }
    friend QString operator+(const QString &a, QChar b) { QString r = a; r.d += b.ucs; return r; }
    friend QString operator+(QChar a, const QString &b) { return QString(a) + b; }
    friend QString operator+(const QString &a, char b) { return a + QChar(b); }
    friend QString operator+(char a, const QString &b) { return QChar(a) + b; }
    friend bool operator==(const QString &a, const QString &b) { return a.d == b.d; }
    friend bool operator!=(const QString &a, const QString &b) { return a.d != b.d; }
    friend bool operator<(const QString &a, const QString &b) { return a.d < b.d; }
    friend bool operator>(const QString &a, const QString &b) { return a.d > b.d; }
    friend bool operator==(const QString &a, const char *b) { return a == QString(b); }
    friend bool operator!=(const QString &a, const char *b) { return a != QString(b); }
    friend bool operator==(const char *a, const QString &b) { return QString(a) == b; }
    friend bool operator!=(const char *a, const QString &b) { return QString(a) != b; }
    friend bool operator==(const QString &a, QChar b) { return a.d.size() == 1 && a.d[0] == b.ucs; }
    friend bool operator!=(const QString &a, QChar b) { return !(a == b); }

private:
    static QString numberU(unsigned long long u, int base) {
        if (u == 0) return QString("0");
        std::string s;
        while (u) { int dgt = int(u % unsigned(base)); s += char(dgt < 10 ? '0' + dgt : 'a' + dgt - 10); u /= unsigned(base); }
        std::reverse(s.begin(), s.end());
        return QString(s.c_str());
    }
};

template <> struct std::hash<QString> {
    size_t operator()(const QString &s) const noexcept { return std::hash<std::u16string>()(s.d); }
};
inline size_t qHash(const QString &s, size_t seed = 0) { return std::hash<QString>()(s) ^ seed; }

/** Qt 6 string view; here a cheap owning copy is good enough for the engine's use. */
class QStringView : public QString {
public:
    QStringView() = default;
    QStringView(const QString &s) : QString(s) {}
    QStringView mid(qsizetype pos, qsizetype n = -1) const { return QStringView(QString::mid(pos, n)); }
    QString toString() const { return *this; }
};

// ---------------------------------------------------------------------------------------------
template <typename T>
class QList : public std::vector<T> {
public:
    using std::vector<T>::vector;
    QList() = default;
    QList(const std::vector<T> &v) : std::vector<T>(v) {}
    qsizetype size() const { return qsizetype(std::vector<T>::size()); }
    qsizetype count() const { return size(); }
    qsizetype length() const { return size(); }
    bool isEmpty() const { return this->empty(); }
    const T &at(qsizetype i) const { return std::vector<T>::at(size_t(i)); }
    T &operator[](qsizetype i) { return std::vector<T>::operator[](size_t(i)); }
    const T &operator[](qsizetype i) const { return std::vector<T>::operator[](size_t(i)); }
    T value(qsizetype i, const T &dflt = T()) const { return i >= 0 && i < size() ? at(i) : dflt; }
    T &first() { return this->front(); }
    const T &first() const { return this->front(); }
    T &last() { return this->back(); }
    const T &last() const { return this->back(); }
    const T &constFirst() const { return this->front(); }
    const T &constLast() const { return this->back(); }
    void append(const T &v) { this->push_back(v); }
    void append(const QList<T> &v) { this->insert(this->end(), v.begin(), v.end()); }
    void prepend(const T &v) { this->insert(this->begin(), v); }
    void removeLast() { this->pop_back(); }
    void removeFirst() { this->erase(this->begin()); }
    void removeAt(qsizetype i) { this->erase(this->begin() + i); }
    T takeLast() { T v = this->back(); this->pop_back(); return v; }
    T takeFirst() { T v = this->front(); this->erase(this->begin()); return v; }
    T takeAt(qsizetype i) { T v = at(i); removeAt(i); return v; }
    bool contains(const T &v) const { return std::find(this->begin(), this->end(), v) != this->end(); }
    qsizetype indexOf(const T &v, qsizetype from = 0) const {
        for (qsizetype i = std::max<qsizetype>(0, from); i < size(); i++) if (at(i) == v) return i;
        return -1;
    }
    QList<T> mid(qsizetype pos, qsizetype n = -1) const {
        if (pos >= size()) return {};
        if (n < 0 || pos + n > size()) n = size() - pos;
        return QList<T>(this->begin() + pos, this->begin() + pos + n);
    }
    QList<T> &operator<<(const T &v) { this->push_back(v); return *this; }
    QList<T> &operator<<(const QList<T> &v) { append(v); return *this; }
    QList<T> &operator+=(const T &v) { this->push_back(v); return *this; }
    QList<T> &operator+=(const QList<T> &v) { append(v); return *this; }
    void resize(qsizetype n) { std::vector<T>::resize(size_t(n)); }
    void reserve(qsizetype n) { std::vector<T>::reserve(size_t(n)); }
    void fill(const T &v, qsizetype n = -1) { if (n >= 0) resize(n); std::fill(this->begin(), this->end(), v); }
};
template <typename T> using QVector = QList<T>;

class QStringList : public QList<QString> {
public:
    using QList<QString>::QList;
    QStringList() = default;
    QStringList(const QList<QString> &l) : QList<QString>(l) {}
    QStringList(std::initializer_list<const char *> l) { for (auto s : l) push_back(QString(s)); }
    QString join(const QString &sep) const {
        QString r;
        for (qsizetype i = 0; i < size(); i++) { if (i) r += sep; r += at(i); }
        return r;
    }
    QString join(QChar sep) const { return join(QString(sep)); }
    QString join(const char *sep) const { return join(QString(sep)); }
    bool contains(const QString &s) const { return QList<QString>::contains(s); }
    QStringList &operator<<(const QString &s) { push_back(s); return *this; }
};

inline QStringList QString::split(const QString &sep) const {
    QStringList out;
    if (sep.isEmpty()) { for (char16_t c : d) out.push_back(QString(QChar(c))); return out; }
    size_t p = 0, q;
    while ((q = d.find(sep.d, p)) != std::u16string::npos) { out.push_back(QString(d.substr(p, q - p))); p = q + sep.d.size(); }
    out.push_back(QString(d.substr(p)));
    return out;
}
inline QStringList QString::split(QChar sep) const { return split(QString(sep)); }

template <typename A, typename B> using QPair = std::pair<A, B>;

// ---------------------------------------------------------------------------------------------
/** QMap with Qt iterator semantics: *it is the value; it.key() / it.value(). Ordered by key. */
template <typename K, typename V>
class QMap {
    std::map<K, V> m;
public:
    template <typename It, typename Ref>
    struct Iter {
        It it;
        Ref operator*() const { return it->second; }
        auto operator->() const { return &it->second; }
        const K &key() const { return it->first; }
        Ref value() const { return it->second; }
        Iter &operator++() { ++it; return *this; }
        Iter operator++(int) { Iter t = *this; ++it; return t; }
        Iter &operator--() { --it; return *this; }
        bool operator==(const Iter &o) const { return it == o.it; }
        bool operator!=(const Iter &o) const { return it != o.it; }
    };
    using iterator = Iter<typename std::map<K, V>::iterator, V &>;
    using const_iterator = Iter<typename std::map<K, V>::const_iterator, const V &>;

    QMap() = default;
    QMap(std::initializer_list<std::pair<const K, V>> l) : m(l) {}
    iterator begin() { return {m.begin()}; }
    iterator end() { return {m.end()}; }
    const_iterator begin() const { return {m.begin()}; }
    const_iterator end() const { return {m.end()}; }
    const_iterator cbegin() const { return {m.cbegin()}; }
    const_iterator cend() const { return {m.cend()}; }
    const_iterator constBegin() const { return {m.cbegin()}; }
    const_iterator constEnd() const { return {m.cend()}; }
    iterator find(const K &k) { return {m.find(k)}; }
    const_iterator find(const K &k) const { return {m.find(k)}; }
    const_iterator constFind(const K &k) const { return {m.find(k)}; }
    iterator erase(iterator it) { return {m.erase(it.it)}; }
    V &operator[](const K &k) { return m[k]; }
    V operator[](const K &k) const { return value(k); }
    bool contains(const K &k) const { return m.find(k) != m.end(); }
    V value(const K &k, const V &dflt = V()) const { auto it = m.find(k); return it == m.end() ? dflt : it->second; }
    iterator insert(const K &k, const V &v) { m[k] = v; return find(k); }
    qsizetype remove(const K &k) { return qsizetype(m.erase(k)); }
    void clear() { m.clear(); }
    bool isEmpty() const { return m.empty(); }
    bool empty() const { return m.empty(); }
    qsizetype size() const { return qsizetype(m.size()); }
    qsizetype count() const { return size(); }
    QList<K> keys() const { QList<K> r; for (auto &p : m) r.push_back(p.first); return r; }
    QList<V> values() const { QList<V> r; for (auto &p : m) r.push_back(p.second); return r; }
    friend bool operator==(const QMap &a, const QMap &b) { return a.m == b.m; }
};

template <typename K, typename V>
class QHash : public std::unordered_map<K, V> {
public:
    using std::unordered_map<K, V>::unordered_map;
    bool contains(const K &k) const { return this->find(k) != this->end(); }
    V value(const K &k, const V &dflt = V()) const { auto it = this->find(k); return it == this->end() ? dflt : it->second; }
    void insert(const K &k, const V &v) { (*this)[k] = v; }
    qsizetype remove(const K &k) { return qsizetype(this->erase(k)); }
    bool isEmpty() const { return this->empty(); }
    qsizetype size() const { return qsizetype(std::unordered_map<K, V>::size()); }
    QList<K> keys() const { QList<K> r; for (auto &p : *this) r.push_back(p.first); return r; }
};

template <typename T>
class QSet : public std::unordered_set<T> {
public:
    using std::unordered_set<T>::unordered_set;
    QSet(std::initializer_list<const char *> l) { for (auto s : l) this->insert(T(s)); }
    bool contains(const T &v) const { return this->find(v) != this->end(); }
    bool isEmpty() const { return this->empty(); }
    qsizetype size() const { return qsizetype(std::unordered_set<T>::size()); }
    QSet<T> &operator<<(const T &v) { this->insert(v); return *this; }
};

// ---------------------------------------------------------------------------------------------
/** QRegularExpression subset: ASCII patterns matched against a string (via std::regex). */
class QRegularExpressionMatch {
public:
    bool matched = false;
    std::vector<std::pair<qsizetype, qsizetype>> caps;   // (start, length), -1 start when unmatched
    QString subject;
    bool hasMatch() const { return matched; }
    qsizetype capturedLength(int n = 0) const { return n < int(caps.size()) && caps[size_t(n)].first >= 0 ? caps[size_t(n)].second : 0; }
    qsizetype capturedStart(int n = 0) const { return n < int(caps.size()) ? caps[size_t(n)].first : -1; }
    QString captured(int n = 0) const {
        if (n >= int(caps.size()) || caps[size_t(n)].first < 0) return QString();
        return subject.mid(caps[size_t(n)].first, caps[size_t(n)].second);
    }
};

class QRegularExpression {
public:
    explicit QRegularExpression(const QString &pattern) : re(pattern.toStdString(), std::regex::ECMAScript) {}
    QRegularExpressionMatch match(const QString &s) const { return run(s); }
    QRegularExpressionMatch matchView(const QStringView &s) const { return run(s); }

private:
    std::regex re;
    QRegularExpressionMatch run(const QString &s) const {
        // Map UTF-16 to a byte string where every code unit becomes one byte (non-ASCII -> 0x7F),
        // so match offsets stay in UTF-16 units. The engine's patterns are pure ASCII.
        std::string bytes;
        bytes.reserve(size_t(s.size()));
        for (char16_t c : s.d) bytes += c < 0x80 ? char(c) : char(0x7F);
        QRegularExpressionMatch m;
        m.subject = s;
        std::smatch sm;
        if (std::regex_search(bytes, sm, re)) {
            m.matched = true;
            for (size_t i = 0; i < sm.size(); i++)
                m.caps.push_back(sm[i].matched ? std::make_pair(qsizetype(sm.position(i)), qsizetype(sm.length(i))) : std::make_pair(qsizetype(-1), qsizetype(0)));
        }
        return m;
    }
};
