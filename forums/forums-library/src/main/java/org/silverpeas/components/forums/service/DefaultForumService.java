/*
 * Copyright (C) 2000 - 2020 Silverpeas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * As a special exception to the terms and conditions of version 3.0 of
 * the GPL, you may redistribute this Program in connection with Free/Libre
 * Open Source Software ("FLOSS") applications as described in Silverpeas's
 * FLOSS exception. You should have received a copy of the text describing
 * the FLOSS exception, and it is also available here:
 * "https://www.silverpeas.org/legal/floss_exception.html"
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.silverpeas.components.forums.service;

import org.silverpeas.components.forums.ForumsContentManager;
import org.silverpeas.components.forums.model.Forum;
import org.silverpeas.components.forums.model.ForumDetail;
import org.silverpeas.components.forums.model.ForumPK;
import org.silverpeas.components.forums.model.ForumPath;
import org.silverpeas.components.forums.model.Message;
import org.silverpeas.components.forums.model.MessagePK;
import org.silverpeas.components.forums.model.MessagePath;
import org.silverpeas.components.forums.model.Moderator;
import org.silverpeas.components.forums.subscription.ForumMessageSubscription;
import org.silverpeas.components.forums.subscription.ForumMessageSubscriptionResource;
import org.silverpeas.components.forums.subscription.ForumSubscription;
import org.silverpeas.components.forums.subscription.ForumSubscriptionResource;
import org.silverpeas.core.ResourceReference;
import org.silverpeas.core.annotation.Service;
import org.silverpeas.core.contribution.attachment.AttachmentServiceProvider;
import org.silverpeas.core.contribution.attachment.model.SimpleDocument;
import org.silverpeas.core.contribution.content.wysiwyg.service.WysiwygController;
import org.silverpeas.core.contribution.contentcontainer.content.ContentManagerException;
import org.silverpeas.core.contribution.rating.model.ContributionRatingPK;
import org.silverpeas.core.contribution.rating.service.RatingService;
import org.silverpeas.core.index.indexing.model.FullIndexEntry;
import org.silverpeas.core.index.indexing.model.IndexEngineProxy;
import org.silverpeas.core.index.indexing.model.IndexEntryKey;
import org.silverpeas.core.node.model.NodeDetail;
import org.silverpeas.core.node.model.NodePK;
import org.silverpeas.core.node.service.NodeService;
import org.silverpeas.core.persistence.jdbc.DBUtil;
import org.silverpeas.core.subscription.SubscriptionService;
import org.silverpeas.core.subscription.SubscriptionServiceProvider;
import org.silverpeas.core.subscription.service.ComponentSubscriptionResource;
import org.silverpeas.core.subscription.service.ResourceSubscriptionProvider;
import org.silverpeas.core.subscription.util.SubscriptionSubscriberList;
import org.silverpeas.core.tagcloud.dao.TagCloudPK;
import org.silverpeas.core.tagcloud.model.TagCloud;
import org.silverpeas.core.tagcloud.model.TagCloudUtil;
import org.silverpeas.core.tagcloud.service.TagCloudService;
import org.silverpeas.core.util.StringUtil;

import javax.inject.Inject;
import javax.transaction.Transactional;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static org.silverpeas.core.i18n.I18NHelper.defaultLanguage;

/**
 * Forums service layer which manage forums application
 */
@Service
@Transactional(Transactional.TxType.SUPPORTS)
public class DefaultForumService implements ForumService {
  private static final String RESOURCE_TYPE = "Forum";
  @Inject
  private TagCloudService tagcloud;
  @Inject
  private RatingService notation;
  @Inject
  private NodeService node;
  @Inject
  private ForumsContentManager forumsContentManager;

  @Override
  public Collection<ForumDetail> getForums(Collection<ForumPK> forumPKs) {
    try (Connection con = openConnection()) {
      return ForumsDAO.selectByForumPKs(con, forumPKs);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public Forum getForum(ForumPK forumPK) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getForum(con, forumPK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public ForumPath getForumPath(final ForumPK forumPK) {
    final List<Forum> path = new ArrayList<>();
    try (Connection con = openConnection()) {
      Forum currentForum = ForumsDAO.getForum(con, forumPK);
      if (currentForum != null) {
        path.add(currentForum);
        while (currentForum != null && !currentForum.isRoot()) {
          currentForum = ForumsDAO.getForum(con,
              new ForumPK(forumPK.getInstanceId(), currentForum.getParentIdAsString()));
          if (currentForum != null) {
            path.add(currentForum);
          }
        }
      }
      return new ForumPath(path);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Gets all forums of an instanceId that have not parent forum.
   * @param instanceId
   * @return
   */
  @Override
  public Collection<Forum> getForumRootList(final String instanceId) {
    Collection<String> forumRootIds = getForumSonsIds(new ForumPK(instanceId, "0"));
    Collection<Forum> forumRoots = new ArrayList<>();
    for (String forumRootId : forumRootIds) {
      forumRoots.add(getForum(new ForumPK(instanceId, forumRootId)));
    }
    return forumRoots;
  }

  @Override
  public Collection<Forum> getForumsList(Collection<ForumPK> forumPKs) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getForumsByKeys(con, forumPKs);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public Collection<Message> getThreadsList(Collection<MessagePK> messagePKs) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getThreadsByKeys(con, messagePKs);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public String getForumName(int forumId) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getForumName(con, forumId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public void deleteAll(final String instanceId) {
    try (Connection con = openConnection()) {
      ForumsDAO.deleteAllForums(con, instanceId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public boolean isForumActive(int forumId) {
    try (Connection con = openConnection()) {
      return ForumsDAO.isForumActive(con, forumId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public int getForumParentId(int forumId) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getForumParentId(con, forumId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public String getForumInstanceId(int forumId) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getForumInstanceId(con, forumId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  public String getForumCreatorId(int forumId) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getForumCreatorId(con, forumId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * @param forumPK
   * @return
   */
  @Override
  public List<Forum> getForums(ForumPK forumPK) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getForumsList(con, forumPK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public ForumDetail getForumDetail(ForumPK forumPK) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getForumDetail(con, forumPK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public List<Forum> getForumsByCategory(ForumPK forumPK, String categoryId) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getForumsListByCategory(con, forumPK, categoryId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * @param forumPK
   * @return
   */
  @Override
  public List<String> getForumSonsIds(ForumPK forumPK) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getForumSonsIds(con, forumPK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Verrouille recursivement l'arborescence d'un forum en ecriture a partir de sa primary key
   * @param forumPK la primary key du forum
   * @param level le niveau de verrouillage
   */
  @Override
  public void lockForum(ForumPK forumPK, int level) {
    List<String> sonsIds = getForumSonsIds(forumPK);
    for (String sonsId : sonsIds) {
      lockForum(new ForumPK(forumPK.getComponentName(), sonsId), level);
    }
    try (Connection con = openConnection()) {
      ForumsDAO.lockForum(con, forumPK, level);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Deverrouille recursivement un forum en ecriture a partir de sa primary key
   * @param forumPK la primary key du forum
   * @param level le niveau de verrouillage
   * @return int le code d'erreur
   */
  @Override
  public int unlockForum(ForumPK forumPK, int level) {
    List<String> sonsIds = getForumSonsIds(forumPK);
    for (String sonsId : sonsIds) {
      unlockForum(new ForumPK(forumPK.getComponentName(), sonsId), level);
    }
    try (Connection con = openConnection()) {
      return ForumsDAO.unlockForum(con, forumPK, level);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Supprime un forum et tous ses sous-forums a partir de sa primary key
   * @param forumPK la primary key du forum
   */
  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public void deleteForum(ForumPK forumPK) {
    List<String> sonsIds = getForumSonsIds(forumPK);
    for (String sonsId : sonsIds) {
      deleteForum(new ForumPK(forumPK.getComponentName(), sonsId));
    }
    try (Connection con = openConnection()) {
      // Deleting subscriptions
      getSubscribeService().unsubscribeByResource(ForumSubscriptionResource.from(forumPK));

      // Recuperation des ids de messages
      List<String> messagesIds = getMessagesIds(forumPK);
      // Suppression du forum et de ses messages
      ForumsDAO.deleteForum(con, forumPK);
      // Suppression de l'index du forum dans le moteur de recherches
      deleteIndex(forumPK);
      // Suppression de l'index de chaque message dans le moteur de recherches
      for (String messagesId : messagesIds) {
        deleteMessage(new MessagePK(forumPK.getComponentName(), messagesId));
      }
      forumsContentManager.deleteSilverContent(con, forumPK);
      deleteTagCloud(forumPK);
      deleteNotation(forumPK);
    } catch (ContentManagerException | SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Cree un nouveau forum dans la datasource
   * @param forumPK la primary key
   * @param forumName nom du forum
   * @param forumDescription description du forum
   * @param forumCreator l'id du createur du forum
   * @param forumParent l'id du forum parent
   * @param categoryId l'id de la categorie
   * @param keywords
   * @return String l'id du nouveau forum
   * @author frageade
   */
  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public int createForum(ForumPK forumPK, String forumName, String forumDescription,
      String forumCreator, int forumParent, String categoryId, String keywords) {
    try (Connection con = openConnection()) {
      int forumId = ForumsDAO
          .createForum(con, forumPK, forumName, forumDescription, forumCreator, forumParent,
              categoryId);
      forumPK.setId(String.valueOf(forumId));
      createIndex(forumPK);
      forumsContentManager.createSilverContent(con, forumPK, forumCreator);
      createTagCloud(forumPK, keywords);
      return forumId;
    } catch (ContentManagerException | SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Met a jour les informations sur un forum dans la datasource
   * @param forumPK la primary key du forum
   * @param forumName nom du forum
   * @param forumDescription description du forum
   * @param forumParent l'id du forum parent
   * @param categoryId l'id de la catégorie
   * @param keywords the keywords associated to this forum.
   */
  @Override
  public void updateForum(ForumPK forumPK, String forumName, String forumDescription,
      int forumParent, String categoryId, String keywords) {
    updateForum(forumPK, forumName, forumDescription, forumParent, categoryId, keywords, true);
  }

  private void updateForum(ForumPK forumPK, String forumName, String forumDescription,
      int forumParent, String categoryId, String keywords, boolean updateTagCloud) {
    try (Connection con = openConnection()) {
      ForumsDAO.updateForum(con, forumPK, forumName, forumDescription, forumParent, categoryId);
      deleteIndex(forumPK);
      createIndex(forumPK);
      if (updateTagCloud) {
        updateTagCloud(forumPK, keywords);
      }
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * @param forumPK
   * @return
   */
  private List<Message> getMessagesList(ForumPK forumPK) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getMessagesList(con, forumPK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public Collection<Message> getMessages(ForumPK forumPK) {
    List<Message> messages = getMessagesList(forumPK);
    String componentId = forumPK.getInstanceId();
    for (Message message : messages) {
      message.setText(getWysiwygContent(componentId, String.valueOf(message.getId())));
    }
    return messages;
  }

  private List<String> getSubjectsIds(ForumPK forumPK) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getSubjectsIds(con, forumPK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  private List<String> getMessagesIds(ForumPK forumPK, int messageParentId) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getMessagesIds(con, forumPK, messageParentId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  private List<String> getMessagesIds(ForumPK forumPK) {
    return getMessagesIds(forumPK, -1);
  }

  @Override
  public int getNbMessages(int forumId, String type, String status) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getNbMessages(con, forumId, type, status);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public int getAuthorNbMessages(String userId, String status) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getAuthorNbMessages(con, userId, status);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public int getNbResponses(int forumId, int messageId, String status) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getNbResponses(con, forumId, messageId, status);
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Retourne le dernier message d'un forum
   * @param forumPK la primary key du forum
   * @param status
   * @return the last message in a forum with the specified status.
   * @author sfariello
   */
  @Override
  public Message getLastMessage(ForumPK forumPK, String status) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getLastMessage(con, forumPK, status);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public Collection getLastMessageRSS(String instanceId, int nbReturned) {
    int countdown = nbReturned;
    // retourne les nbReturned messages des forums de l'instance instanceId
    Collection messages = new ArrayList();
    try (Connection con = openConnection()) {
      // récupère la liste des id des messages
      Collection<String> allMessagesIds = ForumsDAO.getLastMessageRSS(con, instanceId);
      Iterator<String> it = allMessagesIds.iterator();
      // prendre que les nbReturned derniers
      while (it.hasNext() && countdown != 0) {
        String messageId = it.next();
        MessagePK messagePK = new MessagePK(instanceId, messageId);
        messages.add(getMessageInfos(messagePK));
        countdown--;
      }
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
    return messages;
  }

  @Override
  public Message getLastMessage(ForumPK forumPK, int messageParentId, String status) {
    try {
      // liste de tous les messages de la discussion
      List<String> messagesIds = getMessagesIds(forumPK, messageParentId);

      // ajouter la "racine" du message dans la liste de ses réponses
      messagesIds.add(String.valueOf(messageParentId));

      // récupération de la date du dernier message du forum
      return getLastMessage(forumPK, messagesIds, status);
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public Message getLastMessage(ForumPK forumPK, List<String> messageParentIds, String status) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getLastMessage(con, forumPK, messageParentIds, status);
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Retourne vrai s'il y a des messages non lus sur ce forum depuis la dernière visite
   * @param userId l'id de l'utilisateur
   * @param forumPK l'id du forum
   * @param status le status (validé, en attente, ...)
   * @return
   */
  @Override
  public boolean isNewMessageByForum(String userId, ForumPK forumPK, String status) {
    // liste de tous les sujets du forum
    List<String> messagesIds = getSubjectsIds(forumPK);
    int messageParentId;
    for (String messagesId : messagesIds) {
      // pour ce message on recherche la date de la dernière visite
      messageParentId = Integer.parseInt(messagesId);

      if (isNewMessage(userId, forumPK, messageParentId, status)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isNewMessage(String userId, ForumPK forumPK, int messageParentId, String status) {
    try (Connection con = openConnection()) {
      // liste de tous les messages de la discussion
      List<String> messagesIds = getMessagesIds(forumPK, messageParentId);
      // ajouter la "racine" du message dans la liste de ses réponses
      messagesIds.add(String.valueOf(messageParentId));

      // récupération de la date du dernier message du forum
      Message message = getLastMessage(forumPK, messagesIds, status);
      // date du dernier message de la discussion
      Date dateLastMessageBySubject = (message != null ? message.getDate() : null);


      // recherche sur tous les messages de la date de visite la plus ancienne
      // date de la dernière visite pour un message
      Date dateLastVisit = ForumsDAO.getLastVisit(con, userId, messagesIds);

      if (dateLastMessageBySubject == null || dateLastVisit == null ||
          dateLastVisit.before(dateLastMessageBySubject)) {
        // la date de dernière visite de ce message est antérieure à la date du
        // dernier message, il y a donc des réponses non lues pour ce message
        return true;
      }
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
    return false;
  }

  /**
   * enregistre la date de la dernière visite d'un utilisateur sur un forum
   * @param messageId l'id du message
   * @param userId l'id de l'utilisateur
   * @author sfariello
   */
  @Override
  public void setLastVisit(String userId, int messageId) {
    try (Connection con = openConnection()) {
      ForumsDAO.addLastVisit(con, userId, messageId);
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Recupere les infos d'un message
   * @param messagePK la primary key du message
   * @return Vector la liste des champs du message
   * @author frageade
   * @since 04 Octobre 2000
   */
  private List getMessageInfos(MessagePK messagePK) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getMessageInfos(con, messagePK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public Message getMessage(MessagePK messagePK) {
    try (Connection con = openConnection()) {
      Message message = ForumsDAO.getMessage(con, messagePK);
      if (message != null) {
        message.setText(getWysiwygContent(messagePK.getInstanceId(), messagePK.getId()));
      }
      return message;
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public MessagePath getMessagePath(final MessagePK messagePK) {
    final List<Message> path = new ArrayList<>();
    Message currentMessage = getMessage(messagePK);
    if (currentMessage != null) {
      path.add(currentMessage);
      while (currentMessage != null && !currentMessage.isSubject()) {
        currentMessage = getMessage(
            new MessagePK(messagePK.getInstanceId(), currentMessage.getParentIdAsString()));
        if (currentMessage != null) {
          path.add(currentMessage);
        }
      }
    }
    final Message subject = path.iterator().next();
    return new MessagePath(
        getForumPath(new ForumPK(subject.getInstanceId(), subject.getForumIdAsString())), path);
  }

  @Override
  public String getMessageTitle(int messageId) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getMessageTitle(con, messageId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public int getMessageParentId(int messageId) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getMessageParentId(con, messageId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Create new forum message
   * @param messagePK message primary key identifier
   * @param title message title
   * @param authorId message author identifier
   * @param creationDate creation date
   * @param forumId forum identifier
   * @param parentId parent message identifier
   * @param content message content
   * @param keywords the message keywords
   * @param status the message status
   * @return new message identifier
   */
  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public int createMessage(MessagePK messagePK, String title, String authorId, Date creationDate,
      int forumId, int parentId, String content, String keywords, String status) {
    try (Connection con = openConnection()) {
      int messageId =
          ForumsDAO.createMessage(con, title, authorId, creationDate, forumId, parentId, status);
      messagePK.setId(String.valueOf(messageId));
      createTagCloud(messagePK, keywords);
      createWysiwyg(messagePK, content, authorId);
      createIndex(messagePK);
      return messageId;
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public void updateMessage(MessagePK messagePK, String title, String message, String userId,
      String status) {
    try (Connection con = openConnection()) {
      deleteIndex(messagePK);
      ForumsDAO.updateMessage(con, messagePK, title, status);
      updateWysiwyg(messagePK, message, userId);
      createIndex(messagePK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public void updateMessageKeywords(MessagePK messagePK, String keywords) {
    try {
      updateTagCloud(messagePK, keywords);
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Supprime un message et tous ses sous-messages a partir de sa primary key
   * @param messagePK la primary key du message
   * @author frageade
   * @since 04 Octobre 2000
   */
  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public void deleteMessage(MessagePK messagePK) {
    try (Connection con = openConnection()) {
      Collection<String> messageChildren = ForumsDAO.getMessageSons(con, messagePK);
      if (!messageChildren.isEmpty()) {
        for (String child : messageChildren) {
          deleteMessage(new MessagePK(messagePK.getComponentName(), child));
        }
      }

      // Deleting subscriptions
      getSubscribeService().unsubscribeByResource(ForumMessageSubscriptionResource.from(messagePK));

      ForumsDAO.deleteMessage(con, messagePK);
      deleteIndex(messagePK);
      deleteTagCloud(messagePK);
      deleteNotation(messagePK);
      deleteAllAttachments(messagePK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * @param userId
   * @param forumPK
   * @return
   */
  @Override
  public boolean isModerator(String userId, ForumPK forumPK) {
    if (!("0".equals(forumPK.getId()))) {
      try (Connection con = openConnection()) {
        return ForumsDAO.isModerator(con, forumPK, userId);
      } catch (SQLException e) {
        throw new ForumsRuntimeException(e);
      }
    }
    return false;
  }

  /**
   * @param forumPK
   * @param userId
   */
  @Override
  public void addModerator(ForumPK forumPK, String userId) {
    try (Connection con = openConnection()) {
      ForumsDAO.addModerator(con, forumPK, userId);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * @param forumPK
   * @param userId
   */
  @Override
  public void removeModerator(ForumPK forumPK, String userId) {
    try (Connection con = openConnection()) {
      ForumsDAO.removeModerator(con, forumPK, userId);
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * @param forumPK
   */
  @Override
  public void removeAllModerators(ForumPK forumPK) {
    try (Connection con = openConnection()) {
      ForumsDAO.removeAllModerators(con, forumPK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public List<Moderator> getModerators(int forumId) {
    try (Connection con = openConnection()) {
      List<Moderator> moderators = ForumsDAO.getModerators(con, forumId);
      int parentId = getForumParentId(forumId);
      while (parentId > 0) {
        for (Moderator moderatorByInheritance : ForumsDAO.getModerators(con, parentId)) {
          moderatorByInheritance.setByInheritance(true);
          moderators.add(moderatorByInheritance);
        }
        parentId = getForumParentId(parentId);
      }
      return moderators;
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * @param messagePK
   * @param forumPK
   */
  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public void moveMessage(MessagePK messagePK, ForumPK forumPK) {
    try (Connection con = openConnection()) {
      Collection<String> children = ForumsDAO.getMessageSons(con, messagePK);
      if (!children.isEmpty()) {
        for (String childId : children) {
          moveMessage(new MessagePK(messagePK.getComponentName(), childId), forumPK);
        }
      }
      ForumsDAO.moveMessage(con, messagePK, forumPK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Liste tous les sous-messages d'un message
   * @param messagePK la primary key du message pere
   * @return Vector liste des ids fils
   * @author frageade
   */
  public Collection<String> getMessageSons(MessagePK messagePK) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getMessageSons(con, messagePK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Liste tous les sous-messages d'un message récursivement
   * @param messagePK la primary key du message pere
   * @return Vector liste des ids fils
   * @author frageade
   */
  public Collection<String> getAllMessageSons(MessagePK messagePK) {
    try (Connection con = openConnection()) {
      return ForumsDAO.getAllMessageSons(con, messagePK);
    } catch (SQLException e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Subscribe the given user to the given forum message.
   * @param messagePK
   * @param userId
   */
  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public void subscribeMessage(MessagePK messagePK, String userId) {
    getSubscribeService().subscribe(new ForumMessageSubscription(userId, messagePK));
  }

  /**
   * Unsubscribe the given user to the given forum message.
   * @param messagePK
   * @param userId
   */
  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public void unsubscribeMessage(MessagePK messagePK, String userId) {
    getSubscribeService().unsubscribe(new ForumMessageSubscription(userId, messagePK));
  }

  /**
   * Subscribe the given user to the given forum.
   * @param forumPK
   * @param userId
   */
  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public void subscribeForum(final ForumPK forumPK, final String userId) {
    getSubscribeService().subscribe(new ForumSubscription(userId, forumPK));
  }

  /**
   * Unsubscribe the given user to the given forum.
   * @param forumPK
   * @param userId
   */
  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public void unsubscribeForum(final ForumPK forumPK, final String userId) {
    getSubscribeService().unsubscribe(new ForumSubscription(userId, forumPK));
  }

  /**
   * Gets the list of subscribers related to the given forum message primary key.
   * @param messagePK
   * @return
   */
  @Override
  public SubscriptionSubscriberList listAllSubscribers(MessagePK messagePK) {
    return ResourceSubscriptionProvider
        .getSubscribersOfSubscriptionResource(ForumMessageSubscriptionResource.from(messagePK));
  }

  /**
   * Gets the list of subscribers related to the given forum primary key.
   * @param forumPK
   * @return
   */
  @Override
  public SubscriptionSubscriberList listAllSubscribers(final ForumPK forumPK) {
    return ResourceSubscriptionProvider
        .getSubscribersOfSubscriptionResource(ForumSubscriptionResource.from(forumPK));
  }

  /**
   * Gets the list of subscribers to the given component instance identifier.
   * This kind of subscribers come from WEB-Service subscriptions (/services/subscribe/{instanceId})
   * @param instanceId
   * @return
   */
  @Override
  public SubscriptionSubscriberList listAllSubscribers(final String instanceId) {
    return ResourceSubscriptionProvider.getSubscribersOfComponent(instanceId);
  }

  /**
   * Indicates if the given user has subscribed to the given forum message identifier.
   * @param messagePK
   * @param userId
   * @return
   */
  @Override
  public boolean isSubscriber(MessagePK messagePK, String userId) {
    return getSubscribeService()
        .isUserSubscribedToResource(userId, ForumMessageSubscriptionResource.from(messagePK));
  }

  /**
   * Indicates if the given user is subscribed by inheritance to the given forum message
   * identifier.
   * @param messagePK
   * @param userId
   * @return
   */
  @Override
  public boolean isSubscriberByInheritance(final MessagePK messagePK, final String userId) {
    return !isSubscriber(messagePK, userId) && ResourceSubscriptionProvider
        .getSubscribersOfSubscriptionResource(ForumMessageSubscriptionResource.from(messagePK))
        .getAllUserIds().contains(userId);
  }

  /**
   * Indicates if the given user has subscribed to the given forum identifier.
   * @param forumPK
   * @param userId
   * @return
   */
  @Override
  public boolean isSubscriber(final ForumPK forumPK, final String userId) {
    return getSubscribeService()
        .isUserSubscribedToResource(userId, ForumSubscriptionResource.from(forumPK));
  }

  /**
   * Indicates if the given user is subscribed by inheritance to the given forum identifier.
   * @param forumPK
   * @param userId
   * @return
   */
  @Override
  public boolean isSubscriberByInheritance(final ForumPK forumPK, final String userId) {
    return !isSubscriber(forumPK, userId) && ResourceSubscriptionProvider
        .getSubscribersOfSubscriptionResource(ForumSubscriptionResource.from(forumPK))
        .getAllUserIds().contains(userId);
  }

  /**
   * Indicates if the given user has subscribed to the given component instance identifier.
   * @param instanceId
   * @param userId
   * @return
   */
  @Override
  public boolean isSubscriber(final String instanceId, final String userId) {
    return getSubscribeService()
        .isUserSubscribedToResource(userId, ComponentSubscriptionResource.from(instanceId));
  }

  /**
   * Method declaration
   * @param messagePK
   *
   */
  @Override
  public void createIndex(MessagePK messagePK) {
    if (messagePK != null) {
      Message message = getMessage(messagePK);
      String componentId = messagePK.getComponentName();
      String messageId = messagePK.getId();

      FullIndexEntry indexEntry = new FullIndexEntry(componentId, "Message", messageId);
      indexEntry.setTitle(message.getTitle());
      indexEntry.setCreationDate(message.getDate());
      indexEntry.setCreationUser(message.getAuthor());
      WysiwygController.addToIndex(indexEntry, new ResourceReference(messagePK), defaultLanguage);
      IndexEngineProxy.addIndexEntry(indexEntry);
    }

  }

  /**
   * @param messagePK
   */
  private void deleteIndex(MessagePK messagePK) {
    IndexEngineProxy.removeIndexEntry(
        new IndexEntryKey(messagePK.getComponentName(), "Message", messagePK.getId()));
  }

  /**
   * @param forumPK
   */
  @Override
  public void createIndex(ForumPK forumPK) {
    if (forumPK != null) {
      Forum forum = getForum(forumPK);
      FullIndexEntry indexEntry =
          new FullIndexEntry(forumPK.getComponentName(), RESOURCE_TYPE, forumPK.
          getId());
      indexEntry.setTitle(forum.getName());
      indexEntry.setPreview(forum.getDescription());
      IndexEngineProxy.addIndexEntry(indexEntry);
    }
  }

  /**
   * @param forumPK
   */
  private void deleteIndex(ForumPK forumPK) {
    IndexEngineProxy.removeIndexEntry(
        new IndexEntryKey(forumPK.getComponentName(), RESOURCE_TYPE, forumPK.
        getId()));
  }

  /**
   * Open connection
   * @return the connection
   */
  protected Connection openConnection() {
    try {
      return DBUtil.openConnection();
    } catch (SQLException ue) {
      throw new ForumsRuntimeException(ue);
    }
  }

  @Override
  public int getSilverObjectId(ForumPK forumPK) {

    int silverObjectId = -1;
    try {
      int forumId = Integer.parseInt(forumPK.getId());
      String instanceId = forumPK.getComponentName();
      if (instanceId == null || instanceId.length() == 0) {
        instanceId = getForumInstanceId(forumId);
        forumPK.setComponentName(instanceId);
      }
      silverObjectId = forumsContentManager.getSilverContentId(forumPK.getId(), instanceId);
      if (silverObjectId == -1) {
        String creatorId = getForumCreatorId(forumId);
        silverObjectId = forumsContentManager.createSilverContent(null, forumPK, creatorId);
      }
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
    return silverObjectId;
  }

  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public String createCategory(NodeDetail category) {
    try {
      NodePK nodePK = node.createNode(category, new NodeDetail());
      return nodePK.getId();
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public void updateCategory(NodeDetail category) {
    node.setDetail(category);
  }

  @Transactional(Transactional.TxType.REQUIRED)
  @Override
  public void deleteCategory(String categoryId, String instanceId) {
    try {
      // pour cette categorie, rechercher les forums et mettre '0' dans la categorie
      List<Forum> forums = getForumsByCategory(new ForumPK(instanceId, null), categoryId);
      for (Forum forum : forums) {
        ForumPK forumPK = new ForumPK(instanceId, forum.getIdAsString());
        updateForum(forumPK, forum.getName(), forum.getDescription(), forum.getParentId(), null,
            null, false);
      }
      // suppression de la categorie
      NodePK nodePk = new NodePK(categoryId, instanceId);
      node.removeNode(nodePk);
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
  }

  @Override
  public NodeDetail getCategory(NodePK pk) {
    return node.getDetail(pk);
  }

  @Override
  public Collection<NodeDetail> getAllCategories(String instanceId) {
    return node.getChildrenDetails(new NodePK(NodePK.ROOT_NODE_ID, instanceId));

  }

  @Override
  public Collection<Message> getLastThreads(ForumPK forumPK, int count) {
    return getLastThreads(forumPK, count, false);
  }

  @Override
  public Collection<Message> getNotAnsweredLastThreads(ForumPK forumPK, int count) {
    return getLastThreads(forumPK, count, true);
  }

  private Collection<Message> getLastThreads(ForumPK forumPK, int count, boolean notAnswered) {
    try (Connection con = openConnection()) {
      ForumPK[] forumPKs;
      if ("0".equals(forumPK.getId())) {
        // Derniers threads des forums du composant.
        List<String> forumsIds = ForumsDAO.getForumsIds(con, forumPK);
        int forumsCount = forumsIds.size();
        forumPKs = new ForumPK[forumsCount];
        String componentId = forumPK.getComponentName();
        for (int i = 0; i < forumsCount; i++) {
          forumPKs[i] = new ForumPK(componentId, forumsIds.get(i));
        }
      } else {
        // Derniers threads du forum.
        forumPKs = new ForumPK[]{forumPK};
      }

      if (notAnswered) {
        // Threads non répondus.
        return ForumsDAO.getNotAnsweredLastThreads(con, forumPKs, count);
      } else {
        // Tous les threads.
        return ForumsDAO.getLastThreads(con, forumPKs, count);
      }
    } catch (Exception e) {
      throw new ForumsRuntimeException(e);
    }
  }

  /**
   * Create the tagclouds corresponding to the forum detail.
   * @param forumPK theprimary key of the forum.
   * @
   */
  private void createTagCloud(ForumPK forumPK, String keywords) {
    TagCloud tagCloud =
        new TagCloud(forumPK.getComponentName(), forumPK.getId(), TagCloud.TYPE_FORUM);
    createTagCloud(tagCloud, keywords);
  }

  private void createTagCloud(MessagePK messagePK, String keywords) {
    TagCloud tagCloud =
        new TagCloud(messagePK.getComponentName(), messagePK.getId(), TagCloud.TYPE_MESSAGE);
    createTagCloud(tagCloud, keywords);
  }

  private void createTagCloud(TagCloud tags, String keywords) {
    if (keywords != null) {
      String[] words = StringUtil.split(keywords, ' ');
      List<String> tagList = new ArrayList<>(words.length);
      for (String tag : words) {
        String tagKey = TagCloudUtil.getTag(tag);
        if (!tagList.contains(tagKey)) {
          tags.setTag(tagKey);
          tags.setLabel(tag.toLowerCase());
          tagcloud.createTagCloud(tags);
          tagList.add(tagKey);
        }
      }
    }
  }

  /**
   * Delete the tagclouds corresponding to the publication key.
   * @param forumPK The primary key of the forum.
   */
  private void deleteTagCloud(ForumPK forumPK) {
    tagcloud.deleteTagCloud(new TagCloudPK(forumPK.getId(), forumPK.getComponentName()),
        TagCloud.TYPE_FORUM);
  }

  private void deleteTagCloud(MessagePK messagePK) {
    tagcloud.deleteTagCloud(new TagCloudPK(messagePK.getId(), messagePK.getComponentName()),
        TagCloud.TYPE_MESSAGE);
  }

  /**
   * Update the tagclouds corresponding to the publication detail.
   * @param forumPK the primary key of the forum.
   */
  private void updateTagCloud(ForumPK forumPK, String keywords) {
    deleteTagCloud(forumPK);
    createTagCloud(forumPK, keywords);
  }

  private void updateTagCloud(MessagePK messagePK, String keywords) {
    deleteTagCloud(messagePK);
    createTagCloud(messagePK, keywords);
  }

  @Override
  public String getForumTags(ForumPK forumPK) {
    Collection<TagCloud> tagClouds = tagcloud
        .getTagCloudsByElement(forumPK.getComponentName(), forumPK.getId(), TagCloud.TYPE_FORUM);
    return getTags(tagClouds);
  }

  @Override
  public String getMessageTags(MessagePK messagePK) {
    Collection<TagCloud> tagClouds = tagcloud
        .getTagCloudsByElement(messagePK.getComponentName(), messagePK.getId(),
            TagCloud.TYPE_MESSAGE);
    return getTags(tagClouds);
  }

  private String getTags(Collection<TagCloud> tagClouds) {
    StringBuilder sb = new StringBuilder();
    for (TagCloud tag : tagClouds) {
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(tag.getLabel());
    }
    return sb.toString();
  }

  private void deleteNotation(ForumPK forumPK) {
    notation.deleteRating(
        new ContributionRatingPK(forumPK.getId(), forumPK.getComponentName(), RESOURCE_TYPE));
  }

  private void deleteNotation(MessagePK messagePK) {
    notation.deleteRating(
        new ContributionRatingPK(messagePK.getId(), messagePK.getComponentName(), "ForumMessage"));
  }

  protected String getWysiwygContent(String componentId, String messageId) {
    String text = "";
    if (WysiwygController.haveGotWysiwyg(componentId, messageId, defaultLanguage)) {
      text = WysiwygController.load(componentId, messageId, defaultLanguage);
    }
    return text;
  }

  private void createWysiwyg(MessagePK messagePK, String text, String userId) {
    WysiwygController.createUnindexedFileAndAttachment(text, new ResourceReference(messagePK),
        userId, defaultLanguage);
  }

  private void updateWysiwyg(MessagePK messagePK, String text, String userId) {
    String componentId = messagePK.getComponentName();
    String messageId = messagePK.getId();
    if (WysiwygController.haveGotWysiwyg(componentId, messageId, defaultLanguage)) {
      WysiwygController
          .updateFileAndAttachment(text, componentId, messageId, userId, defaultLanguage);
    } else {
      WysiwygController.createUnindexedFileAndAttachment(text, new ResourceReference(messagePK),
          userId, defaultLanguage);
    }
  }

  private void deleteAllAttachments(MessagePK messagePK) {
    ResourceReference foreignKey = new ResourceReference(messagePK);
    List<SimpleDocument> documents = AttachmentServiceProvider.getAttachmentService()
        .listAllDocumentsByForeignKey(foreignKey, null);
    for (SimpleDocument doc : documents) {
      AttachmentServiceProvider.getAttachmentService().deleteAttachment(doc);
    }
  }

  /**
   * Gets instance of centralized subscription services.
   * @return
   */
  protected SubscriptionService getSubscribeService() {
    return SubscriptionServiceProvider.getSubscribeService();
  }
}