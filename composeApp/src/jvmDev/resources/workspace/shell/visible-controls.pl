# Hovel's changes view: every control character in a diff made visible
# (gamero docs/desktop.md, "The changes view"). Bytes in, bytes out, one line
# at a time. Tab and newline pass; ESC shows as ^[, CR as ^M, BS as ^H, other
# C0 controls and DEL in caret form, C1 controls as <U+0080> to <U+009F>, the
# characters that hide or reorder text as <U+202E> and so on, and a byte that
# is not valid UTF-8 as <XX>. Other valid UTF-8 passes unchanged.
#
# Run as /usr/bin/perl -T -C0 visible-controls.pl: -T ignores PERL5OPT and
# PERL5LIB, and -C0 with the raw layers ignores PERL_UNICODE and PERLIO.
use strict;
use warnings;

binmode STDIN, ':raw';
binmode STDOUT, ':raw';

# One valid UTF-8 character of two to four bytes: no overlongs, surrogates
# or code points past U+10FFFF.
my $multibyte = qr/
      [\xC2-\xDF][\x80-\xBF]
    | \xE0[\xA0-\xBF][\x80-\xBF]
    | [\xE1-\xEC\xEE\xEF][\x80-\xBF]{2}
    | \xED[\x80-\x9F][\x80-\xBF]
    | \xF0[\x90-\xBF][\x80-\xBF]{2}
    | [\xF1-\xF3][\x80-\xBF]{3}
    | \xF4[\x80-\x8F][\x80-\xBF]{2}
/x;

# Shown by code point: the C1 controls; the bidi embeddings and overrides
# U+202A-U+202E and isolates U+2066-U+2069; and the zero widths U+200B,
# U+2060 and U+FEFF. ZWNJ, ZWJ, LRM and RLM pass: emoji sequences and real
# Persian, Arabic and Indic text use them.
my $marked = qr/
      \xC2[\x80-\x9F]
    | \xE2\x80[\x8B\xAA-\xAE]
    | \xE2\x81[\xA0\xA6-\xA9]
    | \xEF\xBB\xBF
/x;

# The code point of a two- or three-byte UTF-8 character.
sub code_point {
    my @bytes = map { ord } split //, shift;
    return (($bytes[0] & 0x1F) << 6) | ($bytes[1] & 0x3F) if @bytes == 2;
    return (($bytes[0] & 0x0F) << 12) | (($bytes[1] & 0x3F) << 6) | ($bytes[2] & 0x3F);
}

# A character to show by code point, text to keep, or one byte to show.
sub shown {
    my ($marked, $text, $byte) = @_;
    return sprintf('<U+%04X>', code_point($marked)) if defined $marked;
    return $text if defined $text;
    my $code = ord $byte;
    return '^' . chr($code + 0x40) if $code < 0x20;
    return '^?' if $code == 0x7F;
    return sprintf('<%02X>', $code);
}

while (my $line = <STDIN>) {
    $line =~ s/($marked)|([\t\n\x20-\x7E]+|$multibyte)|(.)/shown($1, $2, $3)/gse;
    print $line;
}
