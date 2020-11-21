appender("consoleAppender", ConsoleAppender) {
  encoder(PatternLayoutEncoder) {
    pattern = "%d{HH:mm:ss.SSS} [%t] %-5level %logger{5} - %msg %ex{full} %n"
  }
}

logger('it.unibo.telestroke', INFO, ["consoleAppender"], false)
root(ERROR, ["consoleAppender"])
