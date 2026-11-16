// Thin, JSON-in/JSON-out API over the moondotgz/casio engine, used by the Android calculator tool.
#pragma once
#include <string>
#include <vector>

namespace casio {

struct Options {
    int angle = 0;        // 0 Deg, 1 Rad, 2 Gra
    int format = 0;       // 0 Norm1, 1 Norm2, 2 Fix, 3 Sci
    int digits = 0;       // Fix decimals / Sci significant digits
    bool exact = true;    // Math output (fractions, √, π) vs decimal
    bool mixed = false;   // a b/c instead of d/c
    bool polar = false;   // complex results as r∠θ
    bool complex = false; // Complex mode
    int base = 0;         // Base-N mode: 2, 8, 10 or 16 (0 = off)
};

// Evaluates one line (statements separated by ':'). Updates Ans/PreAns and variables.
// {"ok":true,"result":"3√(2)/4","decimal":"1.060660172","hasExact":true,"outputs":[{"name":"r","value":"2"}]}
// {"ok":false,"error":"Math ERROR","pos":3}
std::string evaluate(const std::string &expr, const Options &o);

// Polynomial a·xⁿ+… (coefficients highest degree first, degree 2..4) -> {"ok":true,"roots":["…"],"vertex":["x","y"]}
std::string polynomial(const std::vector<double> &coeffs, const Options &o);

// Linear system: n×n matrix row-major + right-hand side -> {"ok":true,"status":"unique","x":["…"]}
std::string linearSystem(int n, const std::vector<double> &a, const std::vector<double> &b, const Options &o);

// Statistics: regType 0 one-variable, 1 a+bx, 2 a+bx+cx², 3 a+b·ln x, 4 a·e^bx, 5 a·b^x, 6 a·x^b, 7 a+b/x
// -> {"ok":true,"values":[{"name":"x̄","value":"…"},…]}
std::string statistics(int regType, const std::vector<double> &x, const std::vector<double> &y, const Options &o);

// Variables A–F, M, x, y, Ans, PreAns as {"A":"…",…} (decimal text), and restore them.
std::string variables(const Options &o);
void setVariable(const std::string &name, double re, double im);
void reset();

// [{"id","symbol","name","category","value","unit"}] and [{"id","category"}]
std::string constants();
std::string conversions();

} // namespace casio
