#include "casio_api.h"

#include "constants.h"
#include "evaluator.h"
#include "format.h"
#include "numeric.h"
#include "parser.h"
#include "stats.h"

#include <cstdio>
#include <mutex>
#include <sstream>

namespace casio {
namespace {

std::mutex g_lock;

Context &ctx() {
    static Context c;
    return c;
}

std::string esc(const std::string &s) {
    std::string o;
    for (char c : s) {
        switch (c) {
        case '"': o += "\\\""; break;
        case '\\': o += "\\\\"; break;
        case '\n': o += "\\n"; break;
        default:
            if (static_cast<unsigned char>(c) < 0x20) { char b[8]; std::snprintf(b, sizeof b, "\\u%04x", c); o += b; }
            else o += c;
        }
    }
    return o;
}
// The engine marks invisible groups with { } (e.g. "{1+6√(2)}/3"). Show a group in parentheses
// when it holds a sum or difference, otherwise just drop the braces ("{3√(2)}/4" -> "3√(2)/4").
QString ungroup(const QString &s) {
    QString out;
    std::vector<qsizetype> open;   // positions in `out` of '(' placeholders
    std::vector<bool> needs;
    for (qsizetype i = 0; i < s.size(); i++) {
        QChar c = s[i];
        if (c == '{') { open.push_back(out.size()); needs.push_back(false); out += QChar(0xE000); continue; }
        if (c == '}' && !open.empty()) {
            qsizetype at = open.back(); open.pop_back();
            bool paren = needs.back(); needs.pop_back();
            if (paren && at > 0 && !needs.empty()) needs.back() = true;
            if (paren) { out.d[size_t(at)] = u'('; out += QChar(')'); }
            else out.d.erase(size_t(at), 1);
            continue;
        }
        // a + or − that isn't a leading sign makes the enclosing group need parentheses
        if (!needs.empty() && (c == '+' || c == QChar(0x2212)) && out.size() > open.back() + 1) needs.back() = true;
        out += c;
    }
    out.remove(QChar(0xE000));
    return out;
}

std::string str(const QString &s) { return "\"" + esc(toDisplayText(ungroup(s)).toStdString()) + "\""; }

Settings settingsFor(const Options &o) {
    Settings s;
    s.angle = o.angle == 1 ? AngleUnit::Rad : o.angle == 2 ? AngleUnit::Gra : AngleUnit::Deg;
    s.format = o.format == 1 ? NumFormat::Norm2 : o.format == 2 ? NumFormat::Fix : o.format == 3 ? NumFormat::Sci : NumFormat::Norm1;
    s.digits = o.digits;
    s.io = o.exact ? IOMode::MathMath : IOMode::MathDec;
    s.frac = o.mixed ? FracFormat::Mixed : FracFormat::Improper;
    s.complexForm = o.polar ? ComplexForm::Polar : ComplexForm::Rect;
    return s;
}

void apply(const Options &o) {
    ctx().settings = settingsFor(o);
    ctx().complexMode = o.complex;
    ctx().base = o.base;
}

std::string value(const Value &v, const Settings &s, bool approx, ForcedForm form = ForcedForm::None) {
    DisplayOpts d;
    d.approx = approx;
    d.form = form;
    if (v.isScalar()) return str(formatComplex(v.z, s, d));
    // matrix / vector: [[a,b],[c,d]]
    std::string out = "\"[";
    for (int r = 0; r < v.rows; r++) {
        if (r) out += ",";
        if (v.isMatrix()) out += "[";
        for (int c = 0; c < (v.isMatrix() ? v.cols : 1); c++) {
            if (c) out += ",";
            out += esc(toDisplayText(formatComplex(v.isMatrix() ? v.at(r, c) : v[r], s, d)).toStdString());
        }
        if (v.isMatrix()) out += "]";
    }
    return out + "]\"";
}

std::string error(const CalcError &e) {
    std::ostringstream o;
    o << "{\"ok\":false,\"error\":\"" << esc(errorName(e.kind).toStdString()) << "\",\"pos\":" << e.pos << "}";
    return o.str();
}

} // namespace

std::string evaluate(const std::string &expr, const Options &o) {
    std::lock_guard<std::mutex> lock(g_lock);
    apply(o);
    try {
        ParseOptions po;
        po.lex.base = o.base;
        auto stmts = parseStatements(QString::fromStdString(expr), po);
        Evaluator ev(ctx());
        Value v;
        for (const auto &s : stmts) {
            v = ev.evaluate(s);
            ctx().pushAns(v);
        }
        std::ostringstream out;
        out << "{\"ok\":true";
        if (o.base) {
            out << ",\"result\":" << str(formatBaseN(qint64(v.re()), o.base)) << ",\"decimal\":" << str(formatBaseN(qint64(v.re()), 10))
                << ",\"hasExact\":false";
        } else {
            const Settings &s = ctx().settings;
            out << ",\"result\":" << value(v, s, !o.exact, ctx().forcedForm)
                << ",\"decimal\":" << value(v, s, true, ctx().forcedForm)
                << ",\"hasExact\":" << (hasExactForm(v, s) ? "true" : "false");
        }
        out << ",\"outputs\":[";
        bool first = true;
        for (const auto &p : ctx().outputs) {
            if (!first) out << ",";
            first = false;
            out << "{\"name\":" << str(p.first) << ",\"value\":" << value(p.second, ctx().settings, !o.exact) << "}";
        }
        out << "]}";
        return out.str();
    } catch (const CalcError &e) {
        return error(e);
    } catch (const std::exception &e) {
        return std::string("{\"ok\":false,\"error\":\"") + esc(e.what()) + "\",\"pos\":-1}";
    }
}

std::string polynomial(const std::vector<double> &coeffs, const Options &o) {
    std::lock_guard<std::mutex> lock(g_lock);
    try {
        if (coeffs.empty() || coeffs[0] == 0) throw CalcError(ErrKind::Math);
        Settings s = settingsFor(o);
        auto roots = num::polyRoots(coeffs);
        std::ostringstream out;
        out << "{\"ok\":true,\"roots\":[";
        for (size_t i = 0; i < roots.size(); i++) {
            if (i) out << ",";
            out << value(Value(roots[i]), s, !o.exact);
        }
        out << "]";
        if (coeffs.size() == 3) {   // vertex of ax²+bx+c
            double a = coeffs[0], b = coeffs[1], c = coeffs[2];
            double vx = -b / (2 * a), vy = c - b * b / (4 * a);
            out << ",\"vertex\":[" << value(Value(vx), s, !o.exact) << "," << value(Value(vy), s, !o.exact) << "]";
        }
        out << "}";
        return out.str();
    } catch (const CalcError &e) {
        return error(e);
    }
}

std::string linearSystem(int n, const std::vector<double> &a, const std::vector<double> &b, const Options &o) {
    std::lock_guard<std::mutex> lock(g_lock);
    try {
        auto A = std::vector<std::vector<double>>(size_t(n), std::vector<double>(size_t(n), 0.0));
        for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) A[size_t(r)][size_t(c)] = a[size_t(r * n + c)];
        std::vector<double> x;
        auto st = num::solveLinear(A, b, x);
        if (st == num::LinStatus::NoSolution) return "{\"ok\":true,\"status\":\"none\",\"x\":[]}";
        if (st == num::LinStatus::Infinite) return "{\"ok\":true,\"status\":\"infinite\",\"x\":[]}";
        Settings s = settingsFor(o);
        std::ostringstream out;
        out << "{\"ok\":true,\"status\":\"unique\",\"x\":[";
        for (size_t i = 0; i < x.size(); i++) { if (i) out << ","; out << value(Value(x[i]), s, !o.exact); }
        out << "]}";
        return out.str();
    } catch (const CalcError &e) {
        return error(e);
    }
}

std::string statistics(int regType, const std::vector<double> &x, const std::vector<double> &y, const Options &o) {
    std::lock_guard<std::mutex> lock(g_lock);
    try {
        StatData d;
        d.x = x;
        if (regType != 0) d.y = y;
        RegType t = static_cast<RegType>(regType);
        StatResult r = computeStats(d, t);
        Settings s = settingsFor(o);
        s.io = IOMode::MathDec;   // statistics are shown as decimals
        QStringList order = r.oneVarOrder;
        if (regType != 0) { order += r.pairedOrder; order += r.regOrder; }
        std::ostringstream out;
        out << "{\"ok\":true,\"type\":" << str(regTypeName(t)) << ",\"values\":[";
        bool first = true;
        for (const auto &k : order) {
            auto v = r.get(k);
            if (!v) continue;
            if (!first) out << ",";
            first = false;
            out << "{\"name\":" << str(k) << ",\"value\":" << value(Value(*v), s, true) << "}";
        }
        out << "]}";
        return out.str();
    } catch (const CalcError &e) {
        return error(e);
    }
}

std::string variables(const Options &o) {
    std::lock_guard<std::mutex> lock(g_lock);
    Settings s = settingsFor(o);
    s.io = IOMode::MathDec;
    std::ostringstream out;
    out.precision(17);
    out << "{";
    bool first = true;
    for (const char *n : {"A", "B", "C", "D", "E", "F", "M", "x", "y", "Ans", "PreAns"}) {
        QString name = QString::fromLatin1(n);
        if (!ctx().vars.contains(name)) continue;
        Value v = ctx().vars.value(name);
        if (!v.isScalar()) continue;
        if (!first) out << ",";
        first = false;
        out << "\"" << n << "\":{\"re\":" << v.z.real() << ",\"im\":" << v.z.imag() << ",\"text\":" << value(v, s, true) << "}";
    }
    out << "}";
    return out.str();
}

void setVariable(const std::string &name, double re, double im) {
    std::lock_guard<std::mutex> lock(g_lock);
    ctx().vars[QString::fromStdString(name)] = Value(cplx(re, im));
}

std::string constants() {
    std::ostringstream out;
    out.precision(12);
    out << "[";
    bool first = true;
    for (const auto &c : scientificConstants()) {
        if (!first) out << ",";
        first = false;
        out << "{\"id\":\"" << esc(c.id.toStdString()) << "\",\"symbol\":\"" << esc(c.symbol.toStdString())
            << "\",\"name\":\"" << esc(c.name.toStdString()) << "\",\"category\":\"" << esc(c.category.toStdString())
            << "\",\"value\":" << c.value << ",\"unit\":\"" << esc(c.unit.toStdString()) << "\"}";
    }
    out << "]";
    return out.str();
}

std::string conversions() {
    std::ostringstream out;
    out << "[";
    bool first = true;
    for (const auto &c : unitConversions()) {
        if (!first) out << ",";
        first = false;
        out << "{\"id\":\"" << esc(c.id.toStdString()) << "\",\"category\":\"" << esc(c.category.toStdString()) << "\"}";
    }
    out << "]";
    return out.str();
}

void reset() {
    std::lock_guard<std::mutex> lock(g_lock);
    ctx() = Context();
}

} // namespace casio
