#!/bin/sh
# 场景：仅基础工具（bash curl ripgrep git）
scenario_name_base() { echo "仅基础工具（bash curl ripgrep git）"; }
install_scenario_base() { install_light_runtimes ""; }