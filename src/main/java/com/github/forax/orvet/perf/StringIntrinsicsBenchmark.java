package com.github.forax.orvet.perf;

import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.intrinsic.StringIntrinsics;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics.$getItem$;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics.$repr$;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics.$slice$;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics.$str$;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._capitalize_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._casefold_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._count_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._endswith_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._find_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._index_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._isalnum_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._isalpha_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._isascii_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._isdecimal_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._isdigit_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._islower_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._isspace_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._isupper_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._join_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._lower_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._lstrip_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._partition_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._removeprefix_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._replace_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._rfind_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._rindex_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._rpartition_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._rsplit_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._rstrip_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._split_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._splitlines_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._startswith_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._strip_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._swapcase_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._title_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._upper_;
import static com.github.forax.orvet.rt.intrinsic.StringIntrinsics._zfill_;

// Benchmarks every public method of StringIntrinsics on a 20 code point string,
// once with an ISO Latin-1 string and once with a non Latin-1 string.
//
//   *_latin1      " Hello, Orvet world "  all code points <= 0xFF,
//                                         internal encoding is Latin-1 (one byte per char)
//   *_not_latin1  " Γεια, Ορβετ κόσμος "  Greek letters > 0xFF,
//                                         internal encoding is UTF-16 (two bytes per char)
//
// The two fixture strings are shape matched: same length (20 code points),
// same punctuation, same number of spaces, and the helper arguments (needle,
// prefix, separator, ...) are also matched, so the only difference between a
// pair of benchmarks is the internal encoding of the strings.
//
// For methods with several overloads, the most representative one is used:
//   _find_/_rfind_/_index_/_rindex_/_count_   (String, Object sub)
//   _split_/_rsplit_                          (String, Object separator) with " "
//   _strip_/_lstrip_/_rstrip_/_splitlines_    (String)
//   _replace_                                 (String, Object, Object)
//   _startswith_/_endswith_                   (String, Object prefix/suffix)
//
//Benchmark                                          Mode  Cnt    Score    Error  Units
//StringIntrinsicsBenchmark.capitalize_latin1        avgt    5   62,806 ±  0,272  ns/op
//StringIntrinsicsBenchmark.capitalize_not_latin1    avgt    5  105,685 ±  3,037  ns/op
//StringIntrinsicsBenchmark.casefold_latin1          avgt    5  367,997 ±  3,515  ns/op
//StringIntrinsicsBenchmark.casefold_not_latin1      avgt    5  759,337 ± 13,497  ns/op
//StringIntrinsicsBenchmark.count_latin1             avgt    5    9,770 ±  5,343  ns/op
//StringIntrinsicsBenchmark.count_not_latin1         avgt    5   85,981 ±  0,823  ns/op
//StringIntrinsicsBenchmark.endswith_latin1          avgt    5    4,152 ±  0,036  ns/op
//StringIntrinsicsBenchmark.endswith_not_latin1      avgt    5   72,547 ±  0,300  ns/op
//StringIntrinsicsBenchmark.find_latin1              avgt    5    5,546 ±  0,097  ns/op
//StringIntrinsicsBenchmark.find_not_latin1          avgt    5   10,731 ±  0,276  ns/op
//StringIntrinsicsBenchmark.getItem_latin1           avgt    5    3,808 ±  0,026  ns/op
//StringIntrinsicsBenchmark.getItem_not_latin1       avgt    5    8,321 ±  0,083  ns/op
//StringIntrinsicsBenchmark.index_latin1             avgt    5    5,662 ±  0,114  ns/op
//StringIntrinsicsBenchmark.index_not_latin1         avgt    5   10,624 ±  0,123  ns/op
//StringIntrinsicsBenchmark.isalnum_latin1           avgt    5   11,204 ±  0,329  ns/op
//StringIntrinsicsBenchmark.isalnum_not_latin1       avgt    5    9,979 ±  0,185  ns/op
//StringIntrinsicsBenchmark.isalpha_latin1           avgt    5    3,551 ±  0,034  ns/op
//StringIntrinsicsBenchmark.isalpha_not_latin1       avgt    5    9,102 ±  0,043  ns/op
//StringIntrinsicsBenchmark.isascii_latin1           avgt    5    6,856 ±  0,681  ns/op
//StringIntrinsicsBenchmark.isascii_not_latin1       avgt    5    2,552 ±  0,176  ns/op
//StringIntrinsicsBenchmark.isdecimal_latin1         avgt    5    3,456 ±  0,038  ns/op
//StringIntrinsicsBenchmark.isdecimal_not_latin1     avgt    5    8,945 ±  0,143  ns/op
//StringIntrinsicsBenchmark.isdigit_latin1           avgt    5    5,897 ±  0,547  ns/op
//StringIntrinsicsBenchmark.isdigit_not_latin1       avgt    5    6,004 ±  0,068  ns/op
//StringIntrinsicsBenchmark.islower_latin1           avgt    5   15,625 ±  0,146  ns/op
//StringIntrinsicsBenchmark.islower_not_latin1       avgt    5   51,778 ±  1,556  ns/op
//StringIntrinsicsBenchmark.isspace_latin1           avgt    5   11,720 ±  0,430  ns/op
//StringIntrinsicsBenchmark.isspace_not_latin1       avgt    5   12,292 ±  0,404  ns/op
//StringIntrinsicsBenchmark.isupper_latin1           avgt    5   15,816 ±  0,196  ns/op
//StringIntrinsicsBenchmark.isupper_not_latin1       avgt    5   52,795 ±  0,514  ns/op
//StringIntrinsicsBenchmark.join_latin1              avgt    5   82,711 ±  8,509  ns/op
//StringIntrinsicsBenchmark.join_not_latin1          avgt    5   75,992 ±  2,493  ns/op
//StringIntrinsicsBenchmark.lower_latin1             avgt    5   20,984 ±  0,537  ns/op
//StringIntrinsicsBenchmark.lower_not_latin1         avgt    5   69,342 ±  0,725  ns/op
//StringIntrinsicsBenchmark.lstrip_latin1            avgt    5   10,042 ±  0,404  ns/op
//StringIntrinsicsBenchmark.lstrip_not_latin1        avgt    5   70,704 ±  0,529  ns/op
//StringIntrinsicsBenchmark.partition_latin1         avgt    5   15,350 ±  1,086  ns/op
//StringIntrinsicsBenchmark.partition_not_latin1     avgt    5   29,459 ±  3,185  ns/op
//StringIntrinsicsBenchmark.removeprefix_latin1      avgt    5    5,436 ±  0,513  ns/op
//StringIntrinsicsBenchmark.removeprefix_not_latin1  avgt    5   15,829 ±  0,443  ns/op
//StringIntrinsicsBenchmark.removesuffix_latin1      avgt    5    6,438 ±  0,071  ns/op
//StringIntrinsicsBenchmark.removesuffix_not_latin1  avgt    5   32,897 ±  0,456  ns/op
//StringIntrinsicsBenchmark.replace_latin1           avgt    5   70,419 ±  3,089  ns/op
//StringIntrinsicsBenchmark.replace_not_latin1       avgt    5  155,090 ±  2,979  ns/op
//StringIntrinsicsBenchmark.repr_latin1              avgt    5   58,965 ±  0,336  ns/op
//StringIntrinsicsBenchmark.repr_not_latin1          avgt    5   94,303 ±  0,808  ns/op
//StringIntrinsicsBenchmark.rfind_latin1             avgt    5    8,085 ±  0,647  ns/op
//StringIntrinsicsBenchmark.rfind_not_latin1         avgt    5   14,241 ±  0,045  ns/op
//StringIntrinsicsBenchmark.rindex_latin1            avgt    5    7,935 ±  0,076  ns/op
//StringIntrinsicsBenchmark.rindex_not_latin1        avgt    5   14,232 ±  0,064  ns/op
//StringIntrinsicsBenchmark.rpartition_latin1        avgt    5   19,861 ± 14,904  ns/op
//StringIntrinsicsBenchmark.rpartition_not_latin1    avgt    5   36,036 ±  1,789  ns/op
//StringIntrinsicsBenchmark.rsplit_latin1            avgt    5  194,556 ± 11,052  ns/op
//StringIntrinsicsBenchmark.rsplit_not_latin1        avgt    5  209,501 ±  1,112  ns/op
//StringIntrinsicsBenchmark.rstrip_latin1            avgt    5   10,691 ±  1,514  ns/op
//StringIntrinsicsBenchmark.rstrip_not_latin1        avgt    5   67,862 ±  0,279  ns/op
//StringIntrinsicsBenchmark.slice_latin1             avgt    5   10,402 ±  0,115  ns/op
//StringIntrinsicsBenchmark.slice_not_latin1         avgt    5   24,461 ±  1,731  ns/op
//StringIntrinsicsBenchmark.split_latin1             avgt    5  116,405 ±  1,913  ns/op
//StringIntrinsicsBenchmark.split_not_latin1         avgt    5  206,020 ±  1,138  ns/op
//StringIntrinsicsBenchmark.splitlines_latin1        avgt    5   17,018 ±  0,092  ns/op
//StringIntrinsicsBenchmark.splitlines_not_latin1    avgt    5   20,203 ±  0,176  ns/op
//StringIntrinsicsBenchmark.startswith_latin1        avgt    5    4,117 ±  0,042  ns/op
//StringIntrinsicsBenchmark.startswith_not_latin1    avgt    5   69,709 ±  0,811  ns/op
//StringIntrinsicsBenchmark.str_latin1               avgt    5    0,421 ±  0,003  ns/op
//StringIntrinsicsBenchmark.str_not_latin1           avgt    5    0,428 ±  0,031  ns/op
//StringIntrinsicsBenchmark.strip_latin1             avgt    5   12,866 ±  1,110  ns/op
//StringIntrinsicsBenchmark.strip_not_latin1         avgt    5   73,241 ±  0,459  ns/op
//StringIntrinsicsBenchmark.swapcase_latin1          avgt    5  437,081 ± 86,148  ns/op
//StringIntrinsicsBenchmark.swapcase_not_latin1      avgt    5  817,218 ± 24,321  ns/op
//StringIntrinsicsBenchmark.title_latin1             avgt    5  189,320 ± 52,096  ns/op
//StringIntrinsicsBenchmark.title_not_latin1         avgt    5  308,926 ± 15,771  ns/op
//StringIntrinsicsBenchmark.upper_latin1             avgt    5   23,513 ±  0,349  ns/op
//StringIntrinsicsBenchmark.upper_not_latin1         avgt    5   68,639 ±  0,690  ns/op
//StringIntrinsicsBenchmark.zfill_latin1             avgt    5   15,006 ±  1,303  ns/op
//StringIntrinsicsBenchmark.zfill_not_latin1         avgt    5   15,784 ±  0,105  ns/op

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class StringIntrinsicsBenchmark {

  // -- fixtures

  // 20 code points, all <= 0xFF: internal encoding is Latin-1, one byte per char
  String latin1;
  // 20 code points with Greek letters (max 0x3CC): internal encoding is UTF-16, two bytes per char
  String notLatin1;

  public StringIntrinsicsBenchmark() {
    this.latin1 = " Hello, Orvet world ";
    this.notLatin1 = " Γεια, Ορβετ κόσμος ";
    super();
  }

  // -- shared arguments, shape matched between the two encodings

  static final Object INDEX_5 = 5L;                                                // string[5]
  static final Object WIDTH_30 = 30L;                                              // zfill(30)
  static final PySlice SLICE_2_TO_MINUS_2 = PySlice.of(2L, -2L, PyNone.INSTANCE);  // s[2:-2]

  static final String SUB_LATIN1 = "rvet";         // occurs once
  static final String SUB_NOT_LATIN1 = "ρβετ";     // occurs once
  static final String PREFIX_LATIN1 = " Hello";
  static final String PREFIX_NOT_LATIN1 = " Γεια";
  static final String SUFFIX_LATIN1 = "world ";
  static final String SUFFIX_NOT_LATIN1 = "κόσμος ";
  static final String SEPARATOR_COMMA = ",";
  static final String SEPARATOR_SPACE = " ";
  static final String OLD_LATIN1 = "o";            // occurs twice
  static final String OLD_NOT_LATIN1 = "ε";        // occurs twice
  static final String NEW_LATIN1 = "0";
  static final String NEW_NOT_LATIN1 = "Έ";
  static final String JOIN_SEPARATOR = ", ";
  static final PyList<String> JOIN_ITEMS_LATIN1 = new PyList<>("Hello", "Orvet", "world");
  static final PyList<String> JOIN_ITEMS_NOT_LATIN1 = new PyList<>("Γεια", "Ορβετ", "κόσμος");

  // -- canonical operations: $getItem$, $slice$, $str$, $repr$

  @Benchmark
  public Object getItem_latin1() {
    return $getItem$(latin1, INDEX_5);
  }

  @Benchmark
  public Object getItem_not_latin1() {
    return $getItem$(notLatin1, INDEX_5);
  }

  @Benchmark
  public String slice_latin1() {
    return $slice$(latin1, SLICE_2_TO_MINUS_2);
  }

  @Benchmark
  public String slice_not_latin1() {
    return $slice$(notLatin1, SLICE_2_TO_MINUS_2);
  }

  @Benchmark
  public String str_latin1() {
    return $str$(latin1);
  }

  @Benchmark
  public String str_not_latin1() {
    return $str$(notLatin1);
  }

  @Benchmark
  public String repr_latin1() {
    return $repr$(latin1);
  }

  @Benchmark
  public String repr_not_latin1() {
    return $repr$(notLatin1);
  }

  // -- case conversion: _lower_, _upper_, _swapcase_, _capitalize_, _title_, _casefold_

  @Benchmark
  public Object lower_latin1() {
    return _lower_(latin1);
  }

  @Benchmark
  public Object lower_not_latin1() {
    return _lower_(notLatin1);
  }

  @Benchmark
  public Object upper_latin1() {
    return _upper_(latin1);
  }

  @Benchmark
  public Object upper_not_latin1() {
    return _upper_(notLatin1);
  }

  @Benchmark
  public Object swapcase_latin1() {
    return _swapcase_(latin1);
  }

  @Benchmark
  public Object swapcase_not_latin1() {
    return _swapcase_(notLatin1);
  }

  @Benchmark
  public Object capitalize_latin1() {
    return _capitalize_(latin1);
  }

  @Benchmark
  public Object capitalize_not_latin1() {
    return _capitalize_(notLatin1);
  }

  @Benchmark
  public Object title_latin1() {
    return _title_(latin1);
  }

  @Benchmark
  public Object title_not_latin1() {
    return _title_(notLatin1);
  }

  @Benchmark
  public Object casefold_latin1() {
    return _casefold_(latin1);
  }

  @Benchmark
  public Object casefold_not_latin1() {
    return _casefold_(notLatin1);
  }

  // -- search: _find_, _rfind_, _index_, _rindex_, _count_, _startswith_, _endswith_,
  //            _partition_, _rpartition_

  @Benchmark
  public Object find_latin1() {
    return _find_(latin1, SUB_LATIN1);
  }

  @Benchmark
  public Object find_not_latin1() {
    return _find_(notLatin1, SUB_NOT_LATIN1);
  }

  @Benchmark
  public Object rfind_latin1() {
    return _rfind_(latin1, SUB_LATIN1);
  }

  @Benchmark
  public Object rfind_not_latin1() {
    return _rfind_(notLatin1, SUB_NOT_LATIN1);
  }

  @Benchmark
  public Object index_latin1() {
    return _index_(latin1, SUB_LATIN1);
  }

  @Benchmark
  public Object index_not_latin1() {
    return _index_(notLatin1, SUB_NOT_LATIN1);
  }

  @Benchmark
  public Object rindex_latin1() {
    return _rindex_(latin1, SUB_LATIN1);
  }

  @Benchmark
  public Object rindex_not_latin1() {
    return _rindex_(notLatin1, SUB_NOT_LATIN1);
  }

  @Benchmark
  public Object count_latin1() {
    return _count_(latin1, SUB_LATIN1);
  }

  @Benchmark
  public Object count_not_latin1() {
    return _count_(notLatin1, SUB_NOT_LATIN1);
  }

  @Benchmark
  public Object startswith_latin1() {
    return _startswith_(latin1, PREFIX_LATIN1);
  }

  @Benchmark
  public Object startswith_not_latin1() {
    return _startswith_(notLatin1, PREFIX_NOT_LATIN1);
  }

  @Benchmark
  public Object endswith_latin1() {
    return _endswith_(latin1, SUFFIX_LATIN1);
  }

  @Benchmark
  public Object endswith_not_latin1() {
    return _endswith_(notLatin1, SUFFIX_NOT_LATIN1);
  }

  @Benchmark
  public Object partition_latin1() {
    return _partition_(latin1, SEPARATOR_COMMA);
  }

  @Benchmark
  public Object partition_not_latin1() {
    return _partition_(notLatin1, SEPARATOR_COMMA);
  }

  @Benchmark
  public Object rpartition_latin1() {
    return _rpartition_(latin1, SEPARATOR_COMMA);
  }

  @Benchmark
  public Object rpartition_not_latin1() {
    return _rpartition_(notLatin1, SEPARATOR_COMMA);
  }

  // -- split / join / strip / replace / prefix / suffix / pad:
  //    _split_, _rsplit_,  _splitlines_, _join_, _strip_, _lstrip_, _rstrip_, _replace_,
  //    _removeprefix_, _removesuffix_, _zfill_

  @Benchmark
  public Object split_latin1() {
    return _split_(latin1, SEPARATOR_SPACE);
  }

  @Benchmark
  public Object split_not_latin1() {
    return _split_(notLatin1, SEPARATOR_SPACE);
  }

  @Benchmark
  public Object rsplit_latin1() {
    return _rsplit_(latin1, SEPARATOR_SPACE);
  }

  @Benchmark
  public Object rsplit_not_latin1() {
    return _rsplit_(notLatin1, SEPARATOR_SPACE);
  }

  @Benchmark
  public Object splitlines_latin1() {
    return _splitlines_(latin1);
  }

  @Benchmark
  public Object splitlines_not_latin1() {
    return _splitlines_(notLatin1);
  }

  @Benchmark
  public Object join_latin1() {
    return _join_(JOIN_SEPARATOR, JOIN_ITEMS_LATIN1);
  }

  @Benchmark
  public Object join_not_latin1() {
    return _join_(JOIN_SEPARATOR, JOIN_ITEMS_NOT_LATIN1);
  }

  @Benchmark
  public Object strip_latin1() {
    return _strip_(latin1);
  }

  @Benchmark
  public Object strip_not_latin1() {
    return _strip_(notLatin1);
  }

  @Benchmark
  public Object lstrip_latin1() {
    return _lstrip_(latin1);
  }

  @Benchmark
  public Object lstrip_not_latin1() {
    return _lstrip_(notLatin1);
  }

  @Benchmark
  public Object rstrip_latin1() {
    return _rstrip_(latin1);
  }

  @Benchmark
  public Object rstrip_not_latin1() {
    return _rstrip_(notLatin1);
  }

  @Benchmark
  public Object replace_latin1() {
    return _replace_(latin1, OLD_LATIN1, NEW_LATIN1);
  }

  @Benchmark
  public Object replace_not_latin1() {
    return _replace_(notLatin1, OLD_NOT_LATIN1, NEW_NOT_LATIN1);
  }

  @Benchmark
  public Object removeprefix_latin1() {
    return _removeprefix_(latin1, PREFIX_LATIN1);
  }

  @Benchmark
  public Object removeprefix_not_latin1() {
    return _removeprefix_(notLatin1, PREFIX_NOT_LATIN1);
  }

  @Benchmark
  public Object removesuffix_latin1() {
    return StringIntrinsics._removesuffix_(latin1, SUFFIX_LATIN1);
  }

  @Benchmark
  public Object removesuffix_not_latin1() {
    return StringIntrinsics._removesuffix_(notLatin1, SUFFIX_NOT_LATIN1);
  }

  @Benchmark
  public Object zfill_latin1() {
    return _zfill_(latin1, WIDTH_30);
  }

  @Benchmark
  public Object zfill_not_latin1() {
    return _zfill_(notLatin1, WIDTH_30);
  }

  // -- character classification: _isascii_, _isalpha_, _isdecimal_, _isdigit_, _isalnum_,
  //    _isspace_, _islower_, _isupper_

  @Benchmark
  public Object isascii_latin1() {
    return _isascii_(latin1);
  }

  @Benchmark
  public Object isascii_not_latin1() {
    return _isascii_(notLatin1);
  }

  @Benchmark
  public Object isalpha_latin1() {
    return _isalpha_(latin1);
  }

  @Benchmark
  public Object isalpha_not_latin1() {
    return _isalpha_(notLatin1);
  }

  @Benchmark
  public Object isdecimal_latin1() {
    return _isdecimal_(latin1);
  }

  @Benchmark
  public Object isdecimal_not_latin1() {
    return _isdecimal_(notLatin1);
  }

  @Benchmark
  public Object isdigit_latin1() {
    return _isdigit_(latin1);
  }

  @Benchmark
  public Object isdigit_not_latin1() {
    return _isdigit_(notLatin1);
  }

  @Benchmark
  public Object isalnum_latin1() {
    return _isalnum_(latin1);
  }

  @Benchmark
  public Object isalnum_not_latin1() {
    return _isalnum_(notLatin1);
  }

  @Benchmark
  public Object isspace_latin1() {
    return _isspace_(latin1);
  }

  @Benchmark
  public Object isspace_not_latin1() {
    return _isspace_(notLatin1);
  }

  @Benchmark
  public Object islower_latin1() {
    return _islower_(latin1);
  }

  @Benchmark
  public Object islower_not_latin1() {
    return _islower_(notLatin1);
  }

  @Benchmark
  public Object isupper_latin1() {
    return _isupper_(latin1);
  }

  @Benchmark
  public Object isupper_not_latin1() {
    return _isupper_(notLatin1);
  }

  static void main() throws RunnerException {
    var opt = new OptionsBuilder()
        .include(StringIntrinsicsBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}
