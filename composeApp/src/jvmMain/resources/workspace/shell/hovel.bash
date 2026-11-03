# Hovel workspace: bash's --init-file.
#
# bash ignores --init-file in a login shell, so Hovel starts an interactive shell with this file,
# and it reads what a login shell reads, in the same order: /etc/profile, then the first of
# ~/.bash_profile, ~/.bash_login and ~/.profile. Hovel never edits the player's files.

__hovel_path=$HOVEL_PATH_PREPEND
unset HOVEL_PATH_PREPEND

if [ -r /etc/profile ]; then . /etc/profile; fi
if [ -r "$HOME/.bash_profile" ]; then . "$HOME/.bash_profile"
elif [ -r "$HOME/.bash_login" ]; then . "$HOME/.bash_login"
elif [ -r "$HOME/.profile" ]; then . "$HOME/.profile"
fi

# Put Hovel's entries first, in order, wherever the player's files moved them. path_helper in
# /etc/profile moved them to the end.
__hovel_prepend_path() {
  [ -n "$__hovel_path" ] || return 0
  local entry lead="" rest=":$PATH:"
  local -a first
  IFS=: read -r -a first <<< "$__hovel_path"
  for entry in "${first[@]}"; do
    [ -n "$entry" ] || continue
    lead="$lead${lead:+:}$entry"
    while [[ $rest == *":$entry:"* ]]; do rest=${rest//":$entry:"/:}; done
  done
  rest=${rest#:}
  rest=${rest%:}
  PATH="$lead${rest:+:$rest}"
}
__hovel_prepend_path

# And before every prompt, after the player's own PROMPT_COMMAND (a string, or an array in bash 5.1+).
if [[ "$(declare -p PROMPT_COMMAND 2>/dev/null)" == "declare -a"* ]]; then
  PROMPT_COMMAND+=(__hovel_prepend_path)
else
  PROMPT_COMMAND="${PROMPT_COMMAND:+$PROMPT_COMMAND$'\n'}__hovel_prepend_path"
fi
