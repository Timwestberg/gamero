# Hovel's changes view: every control character in a diff made visible
# (gamero docs/desktop.md, "The changes view"). Bytes in, bytes out, one line
# at a time. Tab and newline pass; ESC shows as ^[, CR as ^M, BS as ^H, other
# C0 controls and DEL in caret form, C1 controls as <U+0080> to <U+009F>, and
# a byte that is not valid UTF-8 as <XX>. Valid UTF-8 passes unchanged.
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

# A C1 control, text to keep, or one byte to show.
sub shown {
    my ($c1, $text, $byte) = @_;
    return sprintf('<U+%04X>', ord(substr($c1, 1))) if defined $c1;
    return $text if defined $text;
    my $code = ord $byte;
    return '^' . chr($code + 0x40) if $code < 0x20;
    return '^?' if $code == 0x7F;
    return sprintf('<%02X>', $code);
}

while (my $line = <STDIN>) {
    $line =~ s/(\xC2[\x80-\x9F])|([\t\n\x20-\x7E]+|$multibyte)|(.)/shown($1, $2, $3)/gse;
    print $line;
}
