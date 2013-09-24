package controllers

import org.w3.vs.controllers._
import org.w3.vs.model.{ Job => JobModel, User, JobId, _ }
import play.api.i18n.Messages
import play.api.mvc.Action
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.w3.vs.util.timer._
import org.w3.vs.Graphite
import com.codahale.metrics._
import play.api.libs.json.{ Json => PlayJson, JsValue }
import play.api.libs.iteratee.{Enumeratee, Enumerator, Iteratee}
import play.api.libs.{EventSource, Comet}
import org.w3.vs.view.model.{JobView}
import org.w3.vs.store.Formats._
import org.w3.vs.model.OneTimePlan
import play.api.http.MimeTypes

object Job extends VSController {

  val logger = play.Logger.of("org.w3.vs.controllers.Job")

  def reportByMessage(id: JobId): ActionA = GroupedAssertions.index(id)

  def reportByResource(id: JobId): ActionA = Resources.index(id, None)

  def get(id: JobId): ActionA = Action { implicit req =>
      req.getQueryString("group") match {
        case Some("message") => Redirect(routes.GroupedAssertions.index(id))
        case _ =>               Redirect(routes.Resources.index(id, None))
      }
  }

  def redirect(id: JobId): ActionA = Action { implicit req =>
    Redirect(routes.Job.get(id))
  }

  /*def edit(id: JobId): ActionA = AuthAsyncAction { implicit req => user =>
    val f: Future[PartialFunction[Format, Result]] = for {
      job <- user.getJob(id)
    } yield {
      case Html(_) => Ok(views.html.jobForm(JobForm.fill(job), user, Some(id)))
    }
    f.timer(editName).timer(editTimer)
  }*/

  /*def update(id: JobId): ActionA = AuthAsyncAction { implicit req => user =>
    val result: Future[PartialFunction[Format, Result]] = for {
      form <- Future(JobForm.bind match {
        case Left(form) => throw new InvalidFormException(form)
        case Right(validJobForm) => validJobForm
      })
      job_ <- user.getJob(id)
      job <- form.update(job_).save()
    } yield {
      case Html(_) => SeeOther(routes.Job.get(job.id)).flashing(("success" -> Messages("jobs.updated", job.name)))
      case _ => Ok
    }
    result recover {
      case InvalidFormException(form: JobForm, _) => {
        case Html(_) => BadRequest(views.html.jobForm(form, user, Some(id)))
        case _ => BadRequest
      }
    }
  }*/

  def delete(id: JobId): ActionA = AuthenticatedAction { implicit req => user =>
    for {
      job <- user.getJob(id)
      _ <- job.delete()
    } yield {
      render {
        case Accepts.Html() => SeeOther(routes.Jobs.index.url).flashing(("success" -> Messages("jobs.deleted", job.name)))
        case Accepts.Json() => Ok
      }
    }
  }

  def run(id: JobId): ActionA = AuthenticatedAction { implicit req => user =>
    for {
      job <- user.getJob(id)
      _ <- if (user.isSubscriber) job.run() else Future.successful()
    } yield {
      render {
        case Accepts.Html() => {
          if (user.isSubscriber) {
            SeeOther(routes.Job.get(job.id).url) //.flashing(("success" -> Messages("jobs.run", job.name)))
          } else {
            logger.info(s"Redirected user ${user.email} to store for jobId ${job.id}")
            controllers.OneTimeJob.redirectToStore(OneTimePlan.fromJob(job), job.id)
          }
        }
        case Accepts.Json() => {
          if (user.isSubscriber) {
            Accepted
          } else {
            Status(402) // Payment required
          }
        }
      }
    }
  }

  def stop(id: JobId): ActionA = simpleJobAction(id)(user => job => job.cancel())("jobs.stop")

  import play.api.mvc._

  def dispatcher(implicit id: JobId): ActionA = Action { implicit req =>
    timer(dispatcherName, dispatcherTimer) {
      (for {
        body <- req.body.asFormUrlEncoded
        param <- body.get("action")
        action <- param.headOption
      } yield action.toLowerCase match {
        //case "update" => update(id)(req)
        case "delete" => delete(id)(req)
        case "run" => run(id)(req)
        case "stop" => stop(id)(req)
        case a => BadRequest(views.html.error.generic(List(("error", Messages("debug.unexpected", "unknown action " + a)))))
      }).getOrElse(BadRequest(views.html.error.generic(List(("error", Messages("debug.unexpected", "no action parameter was specified"))))))
    }
  }

  def socket(jobId: JobId, typ: SocketType): Handler = {
    typ match {
      case SocketType.ws => webSocket(jobId)
      case SocketType.events => eventsSocket(jobId)
    }
  }

  def webSocket(jobId: JobId): WebSocket[JsValue] = WebSocket.using[JsValue] { implicit reqHeader =>
    val iteratee = Iteratee.ignore[JsValue]
    val enum: Enumerator[JsValue] = Enumerator.flatten(getUser().map(user => enumerator(jobId, user)))
    (iteratee, enum)
  }

  def eventsSocket(jobId: JobId): ActionA = AuthenticatedAction { implicit req => user =>
    render {
      case AcceptsStream() => Ok.stream(enumerator(jobId, user) &> EventSource())
    }
  }

  private def enumerator(jobId: JobId, user: User): Enumerator[JsValue] = {
    import PlayJson.toJson
    Enumerator.flatten(user.getJob(jobId).map(_.jobDatas())) &> Enumeratee.map { iterator =>
      toJson(iterator.map(JobView(_).toJson))
    }
  }

  private def simpleJobAction(id: JobId)(action: User => JobModel => Any)(msg: String): ActionA = AuthenticatedAction { implicit req => user =>
    for {
      job <- user.getJob(id)
      _ = action(user)(job)
    } yield {
      render {
        case Accepts.Html() => SeeOther(routes.Job.get(job.id).url).flashing(("success" -> Messages(msg, job.name)))
        case Accepts.Json() => Accepted
      }
    }
  }

  val getName = (new controllers.javascript.ReverseJob).get.name
  val getTimer = Graphite.metrics.timer(MetricRegistry.name(Job.getClass, getName))
//  val editName = (new controllers.javascript.ReverseJob).edit.name
//  val editTimer = Metrics.newTimer(Jobs.getClass, editName, MILLISECONDS, SECONDS)
  val dispatcherName = (new controllers.javascript.ReverseJob).dispatcher.name
  val dispatcherTimer = Graphite.metrics.timer(MetricRegistry.name(Job.getClass, dispatcherName))

}
