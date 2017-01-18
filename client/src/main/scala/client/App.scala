package client

import japgolly.scalajs.react._, vdom.all._, extra.router.RouterCtl

import api._
import autowire._
import scalajs.concurrent.JSExecutionContext.Implicits.queue

import org.scalajs.dom.{
  WebSocket,
  MessageEvent,
  Event,
  CloseEvent,
  ErrorEvent,
  window
}
import scala.util.{Success, Failure}

import upickle.default.{read => uread}

object App {
  case class State(
      view: View = View.Editor,
      running: Boolean = false,
      sideBarClosed: Boolean = false,
      websocket: Option[WebSocket] = None,
      dark: Boolean = false,
      console: Boolean = false,
      codemirrorSettings: Option[codemirror.Options] = None,
      inputs: Inputs = Inputs.default,
      outputs: Outputs = Outputs()
  ) {
    def setRunning(v: Boolean)    = copy(running = v)
    def toggleTheme               = copy(dark = !dark)
    def toggleConsole             = copy(console = !console)
    def toggleInstrumentation     = copy(inputs = 
      inputs.copy(isInstrumented = !inputs.isInstrumented)
    )

    def openConsole              = copy(console = true)
    def toggleSidebar            = copy(sideBarClosed = !sideBarClosed)
    def log(line: String): State = log(Seq(line))
    def log(lines: Seq[String]): State =
      copy(outputs = outputs.copy(console = outputs.console ++ lines))
    def setCode(code: String)     = copy(inputs = inputs.copy(code = code))
    def setInputs(inputs: Inputs) = copy(inputs = inputs)
    def setSbtConfigExtra(config: String) =
      copy(inputs = inputs.copy(sbtConfigExtra = config))
    def setView(newView: View) = copy(view = newView)
    def setTarget(target: ScalaTarget) =
      copy(inputs = inputs.copy(target = target))

    def addScalaDependency(scalaDependency: ScalaDependency) =
      copy(
        inputs = inputs.copy(libraries = inputs.libraries + scalaDependency))

    def removeScalaDependency(scalaDependency: ScalaDependency) =
      copy(
        inputs = inputs.copy(libraries = inputs.libraries - scalaDependency))

    def changeDependencyVersion(scalaDependency: ScalaDependency,
                                version: String) = {
      val newScalaDependency = scalaDependency.copy(version = version)
      copy(inputs = inputs.copy(
        libraries = (inputs.libraries - scalaDependency) + newScalaDependency))
    }

    def resetOutputs = copy(outputs = Outputs(), console = false)

    def addProgress(progress: PasteProgress) = {
      addOutputs(progress.compilationInfos, progress.instrumentations)
        .log(progress.output)
        .setRunning(!progress.done)
    }

    def setProgresses(progresses: List[PasteProgress]) = {
      progresses.foldLeft(this) {
        case (state, progress) => state.addProgress(progress)
      }
    }

    def addOutputs(compilationInfos: List[api.Problem],
                   instrumentations: List[api.Instrumentation]) =
      copy(outputs = outputs.copy(
        compilationInfos = outputs.compilationInfos ++ compilationInfos.toSet,
        instrumentations = outputs.instrumentations ++ instrumentations.toSet
      ))
  }

  class Backend(scope: BackendScope[(RouterCtl[Page], Option[Snippet]), State]) {
    def codeChange(newCode: String) =
      scope.modState(_.setCode(newCode))

    def sbtConfigChange(newConfig: String) =
      scope.modState(_.setSbtConfigExtra(newConfig))

    private def connect(id: Long) = CallbackTo[WebSocket] {
      val direct = scope.accessDirect

      def onopen(e: Event): Unit = direct.modState(_.log("Connected.\n"))
      def onmessage(e: MessageEvent): Unit = {
        val progress = uread[PasteProgress](e.data.toString)
        direct.modState(
          _.addOutputs(
            progress.compilationInfos,
            progress.instrumentations
          ).log(progress.output).setRunning(!progress.done)
        )
      }
      def onerror(e: ErrorEvent): Unit =
        direct.modState(_.log(s"Error: ${e.message}"))
      def onclose(e: CloseEvent): Unit =
        direct.modState(_.copy(websocket = None).log(s"Closed: ${e.reason}\n"))

      val protocol = if (window.location.protocol == "https:") "wss" else "ws"
      val uri      = s"$protocol://${window.location.host}/progress/$id"
      val socket   = new WebSocket(uri)

      socket.onopen = onopen _
      socket.onclose = onclose _
      socket.onmessage = onmessage _
      socket.onerror = onerror _
      socket
    }

    def clear(): Callback = scope.modState(_.resetOutputs)

    def run2(e: CloseEvent): Callback = run()
    def run(): Callback = {
      scope.state.flatMap(
        s =>
          Callback.future(
            ApiClient[Api]
              .run(s.inputs)
              .call()
              .map(id =>
                connect(id).attemptTry.flatMap {
                  case Success(ws) => {
                    def clearLogs =
                      scope.modState(
                        _.resetOutputs
                          .openConsole
                          .setRunning(true)
                          .copy(websocket = Some(ws))
                          .log("Connecting...\n")
                      )

                    def urlRewrite =
                      scope.props.flatMap {
                        case (router, snippet) =>
                          router.set(Snippet(id))
                      }

                    clearLogs >> urlRewrite
                  }
                  case Failure(error) =>
                    scope.modState(
                      _.resetOutputs.log(error.toString).setRunning(false)
                    )
              })))
    }
    def run(e: ReactEventI): Callback = run()

    def setView(newView: View)(e: ReactEventI): Callback =
      scope.modState(_.setView(newView))

    def setTarget(target: ScalaTarget)(e: ReactEventI): Callback =
      scope.modState(_.setTarget(target))

    def addScalaDependency(scalaDependency: ScalaDependency): Callback =
      scope.modState(_.addScalaDependency(scalaDependency))

    def removeScalaDependency(scalaDependency: ScalaDependency): Callback =
      scope.modState(_.removeScalaDependency(scalaDependency))

    def changeDependencyVersion(scalaDependency: ScalaDependency,
                                version: String): Callback =
      scope.modState(_.changeDependencyVersion(scalaDependency, version))

    def start(props: (RouterCtl[Page], Option[Snippet])): Callback = {
      val (router, snippet) = props

      snippet match {
        case Some(Snippet(id)) =>
          Callback.future(
            ApiClient[Api]
              .fetch(id)
              .call()
              .map(result =>
                result match {
                  case Some(FetchResult(inputs, progresses)) => {
                    scope.modState(
                      _.setInputs(inputs).setProgresses(progresses).openConsole
                    )
                  }
                  case _ =>
                    scope.modState(_.setCode(s"//paste $id not found"))
              })
          )
        case None => Callback(())
      }
    }

    def toggleTheme(e: ReactEventI): Callback = toggleTheme()
    def toggleTheme(): Callback               = scope.modState(_.toggleTheme)
    def toggleConsole(e: ReactEventI): Callback = scope.modState(_.toggleConsole)
    def toggleInstrumentation(e: ReactEventI): Callback = scope.modState(_.toggleInstrumentation)
  }

  val component = ReactComponentB[(RouterCtl[Page], Option[Snippet])]("App")
    .initialState(State())
    .backend(new Backend(_))
    .renderPS {
      case (scope, (router, snippet), state) => {
        import state._

        val sideStyle =
          if (sideBarClosed) "sidebar-closed"
          else "sidebar-open"

        val theme = if (dark) "dark" else "light"

        div(`class` := "app")(
          SideBar(state, scope.backend),
          MainPannel(state, scope.backend)
        )
      }
    }
    .componentDidMount(s => s.backend.start(s.props))
    .build

  def apply(router: RouterCtl[Page], snippet: Option[Snippet]) =
    component((router, snippet))
}
